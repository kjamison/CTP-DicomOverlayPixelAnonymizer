
package edu.cornell.med.ideal;
/* this version adapted from CTP/source/java/org/rsna/ctp/stdstages/DicomPixelAnonymizer.java */

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import org.apache.log4j.Logger;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.objects.FileObject;
import org.rsna.ctp.pipeline.AbstractPipelineStage;
import org.rsna.ctp.pipeline.Processor;
import org.rsna.ctp.servlets.SummaryLink;
import org.rsna.ctp.stdstages.anonymizer.AnonymizerStatus;
import org.rsna.ctp.stdstages.anonymizer.dicom.DICOMPixelAnonymizer;
import org.rsna.ctp.stdstages.anonymizer.dicom.PixelScript;
import org.rsna.ctp.stdstages.anonymizer.dicom.Regions;
import org.rsna.ctp.stdstages.anonymizer.dicom.Signature;
import org.rsna.server.User;
import org.rsna.util.FileUtil;
import org.w3c.dom.Element;


/* added by Keith */
import org.rsna.ctp.stdstages.*;

/* Keith added from CTP/source/java/org/rsna/ctp/stdstages/anonymizer/dicom/DICOMAnonymizer.java */

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;

import org.dcm4che.data.Dataset;
import org.dcm4che.data.DcmDecodeParam;
import org.dcm4che.data.DcmElement;
import org.dcm4che.data.DcmEncodeParam;
import org.dcm4che.data.DcmObjectFactory;
import org.dcm4che.data.DcmParser;
import org.dcm4che.data.DcmParserFactory;
import org.dcm4che.data.FileFormat;
import org.dcm4che.data.FileMetaInfo;
import org.dcm4che.data.SpecificCharacterSet;
import org.dcm4che.dict.DictionaryFactory;
import org.dcm4che.dict.Status;
import org.dcm4che.dict.TagDictionary;
import org.dcm4che.dict.Tags;
import org.dcm4che.dict.UIDs;
import org.dcm4che.dict.VRs;
  
/**
 * The DicomOverlayPixelAnonymizer program provides a command-line
 * tool for invoking the DicomAnonymizer.
 */
public class DicomOverlayPixelAnonymizer extends AbstractPipelineStage implements Processor, Scriptable {

	static final Logger logger = Logger.getLogger(DicomOverlayPixelAnonymizer.class);

	static final DcmParserFactory pFact = DcmParserFactory.getInstance();
	static final DcmObjectFactory oFact = DcmObjectFactory.getInstance();
	static final DictionaryFactory dFact = DictionaryFactory.getInstance();
	static final TagDictionary tagDictionary = dFact.getDefaultTagDictionary();

  
	public File scriptFile = null;
	PixelScript script = null;
	long lastModified = 0;
  //boolean setBurnedInAnnotation = false;
	boolean log = false;
	boolean test = false;
  

	/**
	 * Construct the DicomPixelAnonymizer PipelineStage.
	 * @param element the XML element from the configuration file
	 * specifying the configuration of the stage.
	 */
	public DicomOverlayPixelAnonymizer(Element element) {
		super(element);
		log = element.getAttribute("log").trim().equals("yes");
		String defaultScriptName = "examples/example-dicom-overlay-pixel-anonymizer.script";
		String scriptName = element.getAttribute("script").trim();
		scriptFile = FileUtil.getFile(scriptName, defaultScriptName);
		getScript();
		if (script == null) logger.warn(name + ": Unable to load script file: " + scriptName);
		//setBurnedInAnnotation = element.getAttribute("setBurnedInAnnotation").trim().equals("yes");
		test = element.getAttribute("test").trim().equals("yes");
	}


  public static AnonymizerStatus anonymize(
		File inFile,
		File outFile,
		Regions regions,
		boolean test) {

      boolean forceIVRLE=false;
      boolean renameToSOPIUID=false;
      
	String exceptions = "";
	BufferedInputStream in = null;
	BufferedOutputStream out = null;
	File tempFile = null;
	byte[] buffer = new byte[4096];
	try {
		//The strategy is to have two copies of the dataset.
		//One (dataset) will be modified. The other (origds)
		//will serve as the original data for reference during
		//the anonymization process.

		//Get the origds (up to the pixels), and close the input stream.
		BufferedInputStream origin = new BufferedInputStream(new FileInputStream(inFile));
		DcmParser origp = pFact.newDcmParser(origin);
		FileFormat origff = origp.detectFileFormat();
		Dataset origds = oFact.newDataset();
		origp.setDcmHandler(origds.getDcmHandler());
		origp.parseDcmFile(origff, Tags.PixelData);
		origin.close();

		//Get the dataset (up to the pixels) and leave the input stream open.
		//This one needs to be left open so we can read the pixels and any
		//data that comes afterward.
		in = new BufferedInputStream(new FileInputStream(inFile));
		DcmParser parser = pFact.newDcmParser(in);
		FileFormat fileFormat = parser.detectFileFormat();
		if (fileFormat == null) throw new IOException("Unrecognized file format: "+inFile);
		Dataset dataset = oFact.newDataset();
		parser.setDcmHandler(dataset.getDcmHandler());
		parser.parseDcmFile(fileFormat, Tags.PixelData);
		
		//Set a default for the SpecificCharacterSet, if necessary, in both datasets
		SpecificCharacterSet cs = origds.getSpecificCharacterSet();
		if (cs == null) {
			origds.putCS(Tags.SpecificCharacterSet, "ISO_IR 100");
			dataset.putCS(Tags.SpecificCharacterSet, "ISO_IR 100");
		}

    ///////// Beginning of overlay handling section
    int olcol = origds.getInt(Tags.OverlayColumns,0);
    int olrow = origds.getInt(Tags.OverlayRows,0);
    
    DcmElement oldata = origds.get(Tags.OverlayData);
    int olvr = oldata.vr();
    ByteBuffer bb = origds.getByteBuffer(Tags.OverlayData);
    
    byte[] ol_bytes = bb.array();
    int[] ol_bits = new int[ol_bytes.length*8];
    
    if(olvr==VRs.OB) {
      
      overlayBytesToBits(ol_bytes,ol_bits);
      overlayBlankRegions(ol_bits, olrow, olcol, regions, test ? 1 : 0);
      overlayBitsToBytes(ol_bits,ol_bytes);
      
    } else if(olvr==VRs.OW) {
      // Can probably figure this out if we have examples of it
      
      // swapBytes(ol_bytes);
      //
      // overlayBytesToBits(ol_bytes,ol_bits);
      // overlayBlankRegions(ol_bits, olrow, olcol, regions, test ? 1 : 0);
      // overlayBitsToBytes(ol_bits,ol_bytes);
      //
      // swapBytes(ol_bytes);
      
      throw new Exception("Unexpected OverlayData value representation: OW");
    } else {
      throw new Exception("Unexpected OverlayData value representation: "+olvr);
    }
    
    ByteBuffer bb_new = ByteBuffer.wrap(ol_bytes);
    dataset.putXX(Tags.OverlayData,olvr,bb_new);
    
    ///////// End of overlay handling section
    
		//Encapsulate everything in a context
		//!!!!!!!DICOMAnonymizerContext context = new DICOMAnonymizerContext(cmds, lkup, intTable, origds, dataset);

		//There are two steps in anonymizing the dataset:
		// 1. Insert any elements that are required by the script
		//    but are missing from the dataset.
		// 2. Walk the tree of the dataset and modify any elements
		//    that have scripts or that match global modifiers.

		//Step 1: insert new elements
		//!!!!!!insertElements(context);

		//Step 2: modify the remaining elements according to the commands
		//!!!!!!!!processElements(context);

		//Write the dataset to a temporary file in the same directory
		File tempDir = outFile.getParentFile();
		tempFile = File.createTempFile("DCMtemp-", ".anon", tempDir);
          out = new BufferedOutputStream(new FileOutputStream(tempFile));

          //Get the SOPInstanceUID in case we need it for the rename.
          String sopiUID = null;
		try {
			sopiUID = dataset.getString(Tags.SOPInstanceUID);
			sopiUID = sopiUID.trim();
		}
		catch (Exception e) {
			logger.warn("Unable to get the SOPInstanceUID.");
			sopiUID = "1";
		}

		//Set the encoding
		DcmDecodeParam fileParam = parser.getDcmDecodeParam();
      	String prefEncodingUID = UIDs.ImplicitVRLittleEndian;
		FileMetaInfo fmi = dataset.getFileMetaInfo();
          if ((fmi != null) && (fileParam.encapsulated || !forceIVRLE)) {
          	prefEncodingUID = fmi.getTransferSyntaxUID();
		}
		DcmEncodeParam encoding = (DcmEncodeParam)DcmDecodeParam.valueOf(prefEncodingUID);
		boolean swap = fileParam.byteOrder != encoding.byteOrder;

          //Create and write the metainfo for the encoding we are using
		fmi = oFact.newFileMetaInfo(dataset, prefEncodingUID);
          dataset.setFileMetaInfo(fmi);
          fmi.write(out);

		//Write the dataset as far as was parsed
		dataset.writeDataset(out, encoding);

		//Write the pixels if the parser actually stopped before pixeldata
          if (parser.getReadTag() == Tags.PixelData) {
              dataset.writeHeader(
                  out,
                  encoding,
                  parser.getReadTag(),
                  parser.getReadVR(),
                  parser.getReadLength());
              if (encoding.encapsulated) {
                  parser.parseHeader();
                  while (parser.getReadTag() == Tags.Item) {
                      dataset.writeHeader(
                          out,
                          encoding,
                          parser.getReadTag(),
                          parser.getReadVR(),
                          parser.getReadLength());
                      writeValueTo(parser, buffer, out, false);
                      parser.parseHeader();
                  }
                  if (parser.getReadTag() != Tags.SeqDelimitationItem) {
                      throw new Exception(
                          "Unexpected Tag: " + Tags.toString(parser.getReadTag()));
                  }
                  if (parser.getReadLength() != 0) {
                      throw new Exception(
                          "(fffe,e0dd), Length:" + parser.getReadLength());
                  }
                  dataset.writeHeader(
                      out,
                      encoding,
                      Tags.SeqDelimitationItem,
                      VRs.NONE,
                      0);
              } 
              else {
                  writeValueTo(parser, buffer, out, swap && (parser.getReadVR() == VRs.OW));
              }
			parser.parseHeader(); //get ready for the next element
		}

		//Now do any elements after the pixels one at a time.
		//This is done to allow streaming of large raw data elements
		//that occur above Tags.PixelData.
		int tag;
		long fileLength = inFile.length();
		while (!parser.hasSeenEOF()
				&& (parser.getStreamPosition() < fileLength)
					&& ((tag=parser.getReadTag()) != -1)
						&& (tag != 0xFFFAFFFA)
						&& (tag != 0xFFFCFFFC)) {
			logger.debug("Post-pixels element: "+Tags.toString(tag)); //!!!!! debug->info
			int len = parser.getReadLength();
			boolean isPrivate = ((tag & 0x10000) != 0);

      /* !!!!!!!!!!!!!!!!!
      String script = context.getScriptFor(tag);
      
			if ( (isPrivate && context.rpg) || ((script == null) && context.rue) || ((script != null) && script.startsWith("@remove()") ) ) {
				//skip this element
				logger.debug("Skipping element: "+Tags.toString(tag));
				parser.setStreamPosition(parser.getStreamPosition() + len);
			}
			else {*/
				//write this element
				logger.debug("Writing element: "+Tags.toString(tag)); //!!!!! debug->info
				dataset.writeHeader(
					out,
					encoding,
					parser.getReadTag(),
					parser.getReadVR(),
					parser.getReadLength());
				writeValueTo(parser, buffer, out, swap);
        //!!!!!!!!}
			logger.info("About to get next post-pixels element:\n" +
					"parser.hasSeenEOF() = "+parser.hasSeenEOF()+"\n" +
					"fileLength          = "+fileLength+"\n" +
					"streamPosition      = "+parser.getStreamPosition()); //!!!!!!!! debug->info
			if (!parser.hasSeenEOF() && (parser.getStreamPosition() < fileLength)) parser.parseHeader();
		}
		out.flush();
		out.close();
		in.close();

		//Rename the temp file to the specified outFile.
		if (renameToSOPIUID) outFile = new File(outFile.getParentFile(),sopiUID+".dcm");
		if (!outFile.delete()) {
			logger.warn("Unable to delete " + outFile);
		}
		if (!tempFile.renameTo(outFile)) {
			logger.warn("Unable to rename "+ tempFile + " to " + outFile);
		}
	}

	catch (Exception e) {
		FileUtil.close(in);
		FileUtil.close(out);
		FileUtil.deleteAll(tempFile);
		//Now figure out what kind of response to return.
		String msg = e.getMessage();
		if (msg == null) {
			msg = "!error! - no message";
			if (logger.isDebugEnabled()) logger.debug("Error call from "+inFile, e);
			else logger.info("Error call from "+inFile);
			return AnonymizerStatus.QUARANTINE(inFile,msg);
		}
		if (msg.contains("!skip!")) {
			return AnonymizerStatus.SKIP(inFile,msg);
		}
		if (msg.contains("!quarantine!")) {
			logger.info("Quarantine call from "+inFile);
			logger.info("...Message: "+msg);
			return AnonymizerStatus.QUARANTINE(inFile,msg);
		}
		logger.info("Unknown exception from "+inFile, e);
		return AnonymizerStatus.QUARANTINE(inFile,msg);
	}
	return AnonymizerStatus.OK(outFile, exceptions);
  }


	private static void writeValueTo(
					DcmParser parser,
					byte[] buffer,
					OutputStream out,
					boolean swap) throws Exception {
		InputStream in = parser.getInputStream();
		int len = parser.getReadLength();
		if (swap && (len & 1) != 0) {
			throw new Exception(
				"Illegal length for swapping value bytes: " + len);
		}
		if (buffer == null) {
			if (swap) {
				int tmp;
				for (int i = 0; i < len; ++i, ++i) {
					tmp = in.read();
					out.write(in.read());
					out.write(tmp);
				}
			} else {
				for (int i = 0; i < len; ++i) {
					out.write(in.read());
				}
			}
		} else {
			byte tmp;
			int c, remain = len;
			while (remain > 0) {
				c = in.read(buffer, 0, Math.min(buffer.length, remain));
				if (c == -1) {
					logger.warn("Unable to read element "+Integer.toHexString(parser.getReadTag()));
					logger.warn("...remain = "+remain);
					throw new EOFException("EOF while reading element value");
				}
				if (swap) {
					if ((c & 1) != 0) {
						buffer[c++] = (byte) in.read();
					}
					for (int i = 0; i < c; ++i, ++i) {
						tmp = buffer[i];
						buffer[i] = buffer[i + 1];
						buffer[i + 1] = tmp;
					}
				}
				out.write(buffer, 0, c);
				remain -= c;
			}
		}
		parser.setStreamPosition(parser.getStreamPosition() + len);
	}
  
  private static int getInt(Dataset ds, int tag, int defaultValue) {
  	try { return ds.getInteger(tag).intValue(); }
  	catch (Exception ex) { return defaultValue; }
  }
  
  
	private static void swapBytes(byte[] bytes) {
		int len = bytes.length & 0xffffFFFE;
		byte b;
		for (int i=0; i<len; i+=2) {
			b = bytes[i];
			bytes[i] = bytes[i+1];
			bytes[i+1] = b;
		}
	}
  
  private static void overlayBytesToBits(byte[] ol_bytes, int[] ol_bits){
  
    int bitidx=0;
    for(int i=0; i < ol_bytes.length; i++)
      for(int j=0; j<8; j++)
        ol_bits[bitidx++]=(int)((ol_bytes[i]>>j) & 0x01);
  }
  
  private static void overlayBitsToBytes(int[] ol_bits, byte[] ol_bytes){
    
    byte bitval, b;
    int bitidx=0;
    for(int i = 0; i < ol_bytes.length; i++) {
      b=0x0;
      for(int j = 0; j < 8; j++) {
        bitval=(byte)(ol_bits[bitidx++] == 0 ? 0x00 : 0x01);
        b=(byte)(b | (bitval<<j));
      }
      ol_bytes[i]=b;
    }
  }
  
  private static void overlayBlankRegions(int[] ol_bits, int numrows, int numcolumns, Regions regions, int value){
    //adapted from blankRegions in CTP/source/java/org/rsna/ctp/stdstages/anonymizer/dicom/DICOMPixelAnonymizer.java
    int bitidx;
    for(int row=0; row<numrows; row++) {
      bitidx=row*numcolumns;
      int[] ranges = regions.getRangesFor(row);
      for (int i=0; i<ranges.length; i+=2) {
        int left = ranges[i];
        int right = Math.min((ranges[i+1] + 1), ol_bits.length );
        
        for (int k=left; k<right; k++) 
          ol_bits[bitidx+k] = value;
      }
    }
  }

	/**
	 * Process a DicomObject, logging the filename and returning the processed object.
	 * If there is no script file, pass the object unmodified.
	 * If the object is not a DicomObject, pass the object unmodified.
	 * @param fileObject the object to process.
	 * @return the processed FileObject.
	 */
	public FileObject process(FileObject fileObject) {
		lastFileIn = new File(fileObject.getFile().getAbsolutePath());
		lastTimeIn = System.currentTimeMillis();
		if ( (fileObject instanceof DicomObject)
				&& (scriptFile != null)
					&& ((DicomObject)fileObject).isImage() ) {
			File file = fileObject.getFile();
			getScript();
			if (script != null) {
				Signature signature = script.getMatchingSignature((DicomObject)fileObject);
				log(fileObject, signature);
				if (signature != null) {
					Regions regions = signature.regions;
					if ((regions != null) && (regions.size() > 0)) {
            //logger.info(name+": regions="+regions+"\n");
            //logger.info(name+": file="+file+"\n");

            
						AnonymizerStatus status = DicomOverlayPixelAnonymizer.anonymize(file, file, regions, test);
						if (status.isOK()) {
              //logger.info(name+": status=isOK: "+status+"\n");
							fileObject = FileObject.getInstance(file);
						}
						else if (status.isQUARANTINE()) {
              //logger.info(name+": status=isQUARANTINE\n");
							if (quarantine != null) quarantine.insert(fileObject);
							lastFileOut = null;
							lastTimeOut = System.currentTimeMillis();
							return null;
						}
						else if (status.isSKIP()) {
						  //logger.info(name+": status=isSKIP\n");
						}; //keep the input object
              
					}
				}
			}
		}

		lastFileOut = new File(fileObject.getFile().getAbsolutePath());
		lastTimeOut = System.currentTimeMillis();
		return fileObject;
	}

	private void log(FileObject fileObject, Signature signature) {
		if (log) {
			if (signature != null)
				logger.info(name+": DicomObject "+fileObject.getUID()+" matched:\n"+signature.script);
			else
				logger.info(name+": DicomObject "+fileObject.getUID()+" did not match any signature.");
		}
	}

	/**
	 * Get the script file.
	 * @return the script file used by this stage.
	 */
	public File[] getScriptFiles() {
		return new File[] { scriptFile };
	}

	//Load the script if necessary
	private void getScript() {
		if ((scriptFile != null) && scriptFile.exists()) {
			long lm = scriptFile.lastModified();
			if (lm > lastModified) {
				script = new PixelScript(scriptFile);
				lastModified = lm;
			}
		}
		else script = null;
	}

	/**
	 * Get the list of links for display on the summary page.
	 * @param user the requesting user.
	 * @return the list of links for display on the summary page.
	 */
	public synchronized LinkedList<SummaryLink> getLinks(User user) {
		LinkedList<SummaryLink> links = super.getLinks(user);
		if (allowsAdminBy(user)) {
			String qs = "?p="+pipeline.getPipelineIndex()+"&s="+stageIndex+"&f=0";
			if (scriptFile != null) {
				links.addFirst( new SummaryLink("/script"+qs, null, "Edit the Anonymizer Script File", false) );
			}
		}
		return links;
	}
}
