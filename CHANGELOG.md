# Changelog

## 0.1.0

Milestone one: the download check.

Nothing in the catalogue is worth writing until a GitHub release can actually be downloaded on the
oldest phone it has to support, and on Android 7.0 that is not a given. GitHub serves release files
from a host whose certificate chains up to ISRG Root X1, and Android only added that root in 7.1.1.
A 7.0 phone would list every app with the right version number and then fail every download, with
an error that reads like a server problem rather than a trust-store one.

- The APK carries ISRG Root X1 as an extra trust anchor alongside the system store, declared in a
  network security config — an API that Android 7.0, this app's own minimum, was the first to
  support. It costs about 1.4 KB.
- A check screen that downloads a real published release and reports what happened at each step:
  which Android the phone runs, whether the phone's own certificates include that root, whether the
  API is reachable, and whether the downloaded bytes match the checksum the release published.
- The same fetch runs twice, once believing only the certificates the phone came with and once also
  believing the bundled root. Comparing the two says whether the bundled root is load-bearing on
  this phone or merely redundant — from one install, with nothing plugged in.
- A button that sends the report, since these phones are never connected to a computer and the
  screen is otherwise the only place the answer exists.

Not here yet: the catalogue itself. This release exists to answer one question on a real phone.
