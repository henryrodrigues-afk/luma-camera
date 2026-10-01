# Scripts de desenvolvimento

Execute os comandos a partir da raiz do projeto. `verification/` reúne verificações e o auxiliar de QA Android; `tools/` reúne exportadores de LUT e referências visuais; `lib/` resolve a versão e os destinos. Os dois comandos antigos de verificação continuam disponíveis por wrappers.

Confira os destinos antes de compilar, sem executar Gradle, instalar ou criar arquivos:

```powershell
.\scripts\Verify.ps1 -Abi arm64-v8a -PlanOnly
```

Compilação, testes JVM e lint; `-Install` também instala no aparelho conectado:

```powershell
.\scripts\Verify.ps1 -Abi arm64-v8a
.\scripts\Verify.ps1 -Abi arm64-v8a -Install
```

Sem `-Abi`, o APK é universal. O build temporário usa `%LOCALAPPDATA%\LumaCamera\verification` para evitar problemas de caminhos com acentos. A versão curta vem de `versionName` em `app/build.gradle.kts`: APK e checksum ficam em `dist/releases/<versão>/apk`; log, lint e testes em `dist/reports/<versão>`, com XML JUnit em `tests/` e lint original em `raw/`. Este script não empacota os arquivos fonte.

`lib/ExportLint.ps1` gera a cópia navegável do lint, ajustando links locais sem alterar o original em `raw/`; links externos e âncoras permanecem. O Verify executa essa etapa automaticamente.

Contratos gráficos com os shaders reais e entradas sintéticas em WebGL/ANGLE:

```powershell
node scripts/VerifyShaders.cjs
```

O relatório vai para `dist/reports/<versão>/shader-verification.json`. Esses contratos não executam EGL/OES Android, câmera, codecs ou modelos no aparelho.

Recursos para edição e referências visuais:

```powershell
node scripts/tools/GenerateLogLut.cjs
node scripts/tools/RenderLogComparison.cjs
node scripts/tools/RenderDesign.cjs
```

LUTs exportadas ficam em `dist/resources/luts`, sem substituir os assets do aplicativo. Imagens ficam em `dist/resources/previews`. O desenho HTML renderizado é a referência histórica 0.7 de `design/archive/0.7/luma-0.7.html`; a comparação Log é sintética.

O auxiliar Android exige um serial de emulador explícito:

```powershell
node scripts/verification/AndroidUi.cjs emulator-5580 dump
node scripts/verification/AndroidUi.cjs emulator-5580 screen camera
node scripts/verification/AndroidUi.cjs emulator-5580 fixture
```

XML e capturas ficam em `dist/reports/<versão>/ui`. `fixture` lê o MP4 sintético de `dist/resources/fixtures/LUMA_UI_FIXTURE.mp4` e o copia para os arquivos privados do app de desenvolvimento no emulador. Referências visuais usam Playwright e Chrome; `LUMA_CHROME` permite informar outro executável Chrome compatível.
