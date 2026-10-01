# Bibliotecas e modelos de terceiros

A licença Apache 2.0 na raiz cobre o código original do Luma Camera. Bibliotecas, ferramentas e modelos de terceiros mantêm suas licenças e condições próprias.

| Componente | Versão | Origem / condições |
| --- | --- | --- |
| AndroidX Media3 | 1.4.1 | [Código e licença Apache 2.0](https://github.com/androidx/media/blob/release/LICENSE) |
| MediaPipe Tasks Vision | 0.10.14 | [MediaPipe / Apache 2.0](https://github.com/google-ai-edge/mediapipe) |
| MagicTouch | modelo float32 v1 | Modelo original Google, sem modificações; [licença incluída](app/src/main/assets/models/MAGIC_TOUCH_LICENSE.txt) e [atribuição/model card](app/src/main/assets/models/MAGIC_TOUCH_NOTICE.txt) |
| ML Kit Selfie Segmentation | 16.0.0-beta6 | SDK binário Google sujeito aos [termos do ML Kit](https://developers.google.com/ml-kit/terms); não é relicenciado pela licença deste projeto |
| Kotlin | 1.9.22 | [Apache 2.0](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) |
| Gradle wrapper | 8.5 | [Apache 2.0](https://github.com/gradle/gradle/blob/master/LICENSE) |

As dependências declaradas em `app/build.gradle.kts` são obtidas de Google Maven/Maven Central. AARs podem incluir componentes transitivos com avisos adicionais. Preserve os avisos desses pacotes ao redistribuir ou modificar uma compilação.

O modelo MagicTouch e suas atribuições são embarcados nos assets. O ML Kit processa imagens localmente; seus termos também descrevem telemetria do SDK. O APK oficial do Luma remove as permissões INTERNET e ACCESS_NETWORK_STATE do manifesto mesclado. A presença do SDK não autoriza afirmar que todo o binário do aplicativo tenha código aberto.
