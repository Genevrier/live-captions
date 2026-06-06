# Keep the sherpa-onnx JNI bridge classes (accessed from native code).
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
