$env:JAVA_HOME = "$env:APPDATA\.minecraft\runtime\java-runtime-delta"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location 'F:\vibe coding工程\sfx\echoworld\nf-1.21.1'
$g = 'C:\Users\一只屑\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat'
& $g runClient --daemon
exit $LASTEXITCODE
