# DevAI V1.11 — build APK local avec Termux

V1.11 ne tente plus d'embarquer un JDK Linux classique dans l'application. Sur Android, le JDK Termux est dans le sandbox de Termux et ne peut pas être exécuté directement par DevAI.

Le bouton **TERMUX** copie le projet dans `/sdcard/DevAI/projects/<nom>` puis demande à Termux de lancer Gradle avec :

- Java 21 Termux
- Gradle installé dans Termux
- Android SDK `~/Android/android-sdk`
- Build-tools 37.0.0

## Préparation Termux

Vérifier :

```bash
java -version
gradle --version
sdkmanager --version
echo $ANDROID_HOME
```

Le projet V1.11 utilise **AGP 9.1.0**, compatible avec Gradle 9.3.1 ou plus récent. Le Gradle 9.8.1 déjà présent sur la tablette est donc utilisable. (Le projet n'utilise plus AGP 8.7.3/Gradle 8.9.)

## Test

1. Installer DevAI V1.11.
2. Ouvrir/créer un projet Android contenant `settings.gradle.kts`, `build.gradle.kts` et `app/`.
3. Appuyer sur **TERMUX**.
4. Dans Termux, le build doit démarrer.
5. Le log est écrit dans :

`/sdcard/DevAI/projects/<nom>/devai-build.log`

6. APK attendu :

`/sdcard/DevAI/projects/<nom>/app/build/outputs/apk/debug/app-debug.apk`

## Important

Le bouton **COMPILER APK** reste le moteur direct historique de DevAI. Sur Android, il ne peut pas accéder au JDK privé de Termux. **TERMUX** est donc la première voie réellement compatible avec le JDK/SDK déjà installés sur la tablette, sans GitHub.

## Autorisations Termux

Pour que le bouton **TERMUX** puisse fonctionner, dans les réglages de Termux :

1. Autoriser l'accès aux fichiers et exécuter `termux-setup-storage` si nécessaire.
2. Dans `~/.termux/termux.properties`, mettre :

```text
allow-external-apps=true
```

3. Redémarrer Termux.
4. Dans Android → Applications → DevAI → Permissions supplémentaires, accorder **Run commands in Termux environment**.
5. DevAI peut demander l'autorisation Android **Accès à tous les fichiers** pour copier le projet dans `/sdcard/DevAI`.
