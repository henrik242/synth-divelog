# JGit references JDK classes Android lacks (Kerberos auth, ProcessHandle); those paths are never taken.
-dontwarn org.ietf.jgss.**
-dontwarn java.lang.ProcessHandle

# JGit creates its message bundles by reflection, fills them by field name and finds the resource by class name.
-keep class * extends org.eclipse.jgit.nls.TranslationBundle { public <init>(); public <fields>; }
