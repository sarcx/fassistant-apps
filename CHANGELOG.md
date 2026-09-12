# Changelog

## 0.2.0

The list itself. The app now shows which Fassistant apps exist, what each has released, and what is
on the phone.

- Apps are found by asking GitHub which of the owner's public repositories carry the topic
  `fassistant`. That topic is the whole list: shipping a new app means tagging its repository, and
  the catalogue picks it up on the next refresh with nothing edited and nothing re-released.
- Each row says what it would do — install, update, or nothing, because it is current.
- GitHub allows sixty unauthenticated requests an hour and counts them per network address, not per
  device, so a laptop on the same connection can use up the phone's allowance. That turned up
  during development rather than in theory. The published manifests are saved on disk, a refresh
  that cannot reach GitHub falls back to them with the age shown, and being rate-limited is
  reported as itself rather than as a failure.
- What is installed is read fresh every time and never saved, because it changes underneath the app
  whenever something is installed or removed.
- The download check moved to its own screen, reached from the button on the list.
- The two certificate steps in that check now report success as well as failure. They were silent
  when they passed, which made a working check look like a broken one.

Not here yet: the buttons that actually install. A row says what it would do; doing it is next, and
needs the permission that lets an app install apps.

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
