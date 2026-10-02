# MapLibre, Ferrostar (JNA + uniffi) and valhalla-mobile ship their own
# consumer rules. Add app-specific rules here.

# sherpa-onnx ships no rules, but its native code reads the Kotlin config
# classes by field name over JNI and calls back into them, so R8 must not
# rename or strip anything in the package.
-keep class com.k2fsa.sherpa.onnx.** { *; }
