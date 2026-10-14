$env:JAVA_HOME = "$env:APPDATA\.minecraft\runtime\java-runtime-delta"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME = 'D:\tools\gradle-home'
Set-Location 'F:\vibe coding¹¤³Ì\sfx\worldreplay\nf-1.21.1'
& 'D:\tools\gradle-9.4.1\bin\gradle.bat' runServer --daemon
