val root = project in file("..")

Test / test := (Test / test dependsOn {
  root / TaskKeys.downloadBitcoind
}).value

Test / test := (Test / test dependsOn {
  root / TaskKeys.downloadLnd
}).value
