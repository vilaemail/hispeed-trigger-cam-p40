# Signing Setup

## Generate a Release Keystore

```bash
keytool -genkey -v -keystore signing/release.keystore \
    -alias hispeed-trigger-cam \
    -keyalg RSA -keysize 2048 -validity 10000
```

## Create keystore.properties

Create `signing/keystore.properties` with your keystore details:

```properties
storeFile=../signing/release.keystore
storePassword=your_password
keyAlias=hispeed-trigger-cam
keyPassword=your_password
```

Both `release.keystore` and `keystore.properties` are gitignored and will never be committed.
