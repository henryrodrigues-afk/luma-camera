# Contribuir com Luma Camera

Abra uma issue com a versão do app, modelo/Android, lente, resolução/FPS e passos para reproduzir. Para áudio/player, inclua duração, comportamento e diagnóstico exportado, removendo informações pessoais antes de publicar.

Faça alterações pequenas e explique o problema, a solução e a validação. Acrescente regressões para erros de lógica. Código Android fica em `app/src/main/`, testes JVM em `app/src/test/`; guias e resultados ficam em `docs/`.

Use JDK 17 e SDK/Build Tools 34. Na raiz:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug -PlumaAbi=arm64-v8a
```

No Windows, também pode usar `scripts/Verify.ps1 -Abi arm64-v8a`. Testes JVM/GLSL não validam câmera, codec, microfone, GPU ou desempenho em um aparelho. Alterações nesses caminhos precisam de clipes novos em ambas as lentes/orientações, áudio, reprodução, interrupções e gravações em partes.

Não inclua material pessoal, diagnósticos privados, chaves de assinatura, `local.properties`, caches, builds ou APKs nos commits. O material distribuído fica nas Releases do GitHub. Preserve as licenças e atribuições dos componentes existentes.
