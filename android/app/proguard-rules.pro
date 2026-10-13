# ML Kit discovers its components from manifest meta-data and creates each registrar by reflection.
# R8 full mode keeps the class names but drops their no-arg constructors, so the barcode scanner never
# registers and BarcodeScanning.getClient() crashes the scan screen.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
