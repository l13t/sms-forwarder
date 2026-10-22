The included `gradle-wrapper.jar` is the official embedded standalone wrapper
from the locally cached Gradle 7.3 binary distribution, copied without changes.
It bootstraps Gradle **8.13**, as configured in `gradle-wrapper.properties`.
The bootstrap was exercised up to the attempted Gradle download in this workspace.

Gradle is distributed under the Apache License 2.0:
https://github.com/gradle/gradle/blob/v7.3.0/LICENSE

With network access, regenerate the wrapper using the configured distribution:

```sh
./gradlew wrapper --gradle-version 8.13 --distribution-type bin
```
