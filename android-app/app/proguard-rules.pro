# JavaMail (com.sun.mail:android-mail / android-activation): i provider SMTP e i tipi MIME sono
# risolti via reflection / file META-INF, quindi vanno tenuti integri nella build minificata.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-keep class mailcap.** { *; }
-dontwarn com.sun.mail.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**
-dontwarn java.awt.**
-dontwarn javax.security.**
