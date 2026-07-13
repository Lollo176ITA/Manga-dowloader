# Jakarta Mail / Eclipse Angus: i provider SMTP e i tipi MIME sono
# risolti via reflection / file META-INF, quindi vanno tenuti integri nella build minificata.
-keep class org.eclipse.angus.mail.** { *; }
-keep class org.eclipse.angus.activation.** { *; }
-keep class jakarta.mail.** { *; }
-keep class jakarta.activation.** { *; }
-keep class mailcap.** { *; }
-dontwarn org.eclipse.angus.mail.**
-dontwarn org.eclipse.angus.activation.**
-dontwarn jakarta.mail.**
-dontwarn jakarta.activation.**
-dontwarn java.awt.**
-dontwarn javax.security.**
