# Micro sans écho (Android)

Application Android qui renvoie en direct le son du micro vers le haut-parleur (ou le casque), en supprimant l'écho et le larsen.

## Comment l'écho est supprimé

1. **Mode communication d'Android** (`MODE_IN_COMMUNICATION` + source `VOICE_COMMUNICATION`) : active le traitement anti-écho du téléphone, le même que pour les appels.
2. **Effets Android** : `AcousticEchoCanceler`, `NoiseSuppressor`, `AutomaticGainControl` (selon ce que le téléphone propose ; l'app affiche ce qui est actif).
3. **Traitement dans l'app** :
   - porte de bruit : coupe l'écho résiduel et le souffle quand personne ne parle (seuil réglable) ;
   - anti-larsen : si un son fort et continu apparaît (sifflement), le volume baisse automatiquement puis remonte doucement ;
   - limiteur : évite la saturation.
4. **Faible latence** : tampons de 10 ms, sortie `PERFORMANCE_MODE_LOW_LATENCY`.

> Avec un casque ou des écouteurs, il n'y a plus du tout d'écho : c'est la solution la plus fiable.

## Obtenir l'APK

- **GitHub Actions** : chaque push lance le workflow *Build APK*. Récupérez l'APK dans l'onglet *Actions* → dernier run → *Artifacts* → `MicSansEcho-apk`.
- **Android Studio** : ouvrez le dossier, puis *Run*.
- **Ligne de commande** (SDK Android installé) : `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

Android 7.0 (API 24) minimum.
