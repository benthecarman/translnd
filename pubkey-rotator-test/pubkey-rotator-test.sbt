Test / test := (Test / test dependsOn {
  Projects.translnd / TaskKeys.downloadBitcoind
}).value

Test / test := (Test / test dependsOn {
  Projects.translnd / TaskKeys.downloadLnd
}).value

publish / skip := true
