# CTP-DicomOverlayPixelAnonymizer
Adapted from [DicomPixelAnonymizer](http://mircwiki.rsna.org/index.php?title=The_CTP_DICOM_Pixel_Anonymizer). The filtering script uses the same format, but currently all entries should begin with:
```
  { [6000,0022].containsIgnoreCase("Siemens MedCom Object Graphics") *
    [6000,0040].equals("G") *
    ... other filters ... }  
```
as this is the only overlay format that has been examined.


## Build
```
cd <root>
ant
```

## Install
```
cd <root>
cp -f ./products/DicomOverlayPixelAnonymizer.jar <CTPDIR>/libraries/
cp -f ./source/files/examples/example-dicom-overlay-pixel-anonymizer.script <CTPDIR>/examples/
cp -f ./source/files/examples/example-dicom-overlay-pixel-anonymizer.script <CTPDIR>/scripts/DicomOverlayPixelAnonymizer.script
```
Now restart CTP
