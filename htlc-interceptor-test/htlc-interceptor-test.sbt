val translnd = project in file("..")

Test / test := (Test / test dependsOn {
  translnd / TaskKeys.downloadBitcoind
}).value

Test / test := (Test / test dependsOn {
  translnd / TaskKeys.downloadLnd
}).value

publish / skip := true
