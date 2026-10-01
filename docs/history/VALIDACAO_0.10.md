# Validação do Luma Camera 0.10

Data: **01/10/2026**. Versão **0.10.0-reliable**, código 10. APK **arm64-v8a**, Android 10 ou superior, para testar no Galaxy A14 5G.

## Evidência local

| Verificação | Resultado |
| --- | --- |
| Compilação integrada ARM64 | BUILD SUCCESSFUL, 3 min 27 s |
| JUnit | **224 testes em 25 suítes**, zero falhas, erros ou ignorados |
| Lint | Zero erros; 19 avisos |
| Shaders/LUT | **83 contratos aprovados** em WebGL/ANGLE |
| Continuidade | **23 arquivos protegidos idênticos à fonte 0.9**, incluindo shaders, modelos/licenças, Log/LUTs, geometria, máscaras e estabilização |
| Assets no APK | Os 10 assets próprios conferidos contra a fonte, incluindo modelo, licenças, shaders e duas LUTs |
| Fonte durante a compilação | 86 entradas acompanhadas; nenhuma mudou após o início da verificação |
| Assinatura | apksigner aprovado; certificado de desenvolvimento preservado |
| Manifesto/ABI | Código 10; mínimo 29/alvo 34; arm64-v8a; câmera, microfone e receiver interno; sem INTERNET/ACCESS_NETWORK_STATE |
| Android/A14 real | **Pendente**; nenhum aparelho conectado no ADB |

Ferramentas: JDK 17, SDK/Build Tools 34, Gradle 8.5, AGP 8.2.2 e Kotlin 1.9.22. Dependências preservadas: Media3 1.4.1, ML Kit 16.0.0-beta6 e MediaPipe Tasks Vision 0.10.14. Execução: `scripts/Verify.ps1 -Abi arm64-v8a` e `node scripts/VerifyShaders.cjs`.

Os novos testes exercitam origem/pacing do relógio, gaps sem aceleração, contador, duração plausível, formatação/unidades/horas, limites de janela/fonte, preservação de rolagem e políticas de recuperação/posição. Não executam as views, MediaRecorder, EGL/OES, codecs Samsung, AudioTrack ou inferência no celular. WebGL usa os assets GLSL atuais com pixels sintéticos, substituindo somente o sampler externo por textura 2D. Não há medida de FPS, aquecimento, bateria, sincronismo audiovisual ou legibilidade nativa no A14.

## Mudanças desta versão

O layout usa a área útil da janela e fonte do sistema para dimensionar dock/painel, empilhar rótulos e preservar altura de texto/toque. Menus conservam a rolagem na mesma categoria. Controles reavaliam suas dependências ao mudar ajustes/estado, evitando disponibilidade antiga capturada na criação do botão. Insets são aplicados uma vez. HUDs/contador/monitores respeitam o orçamento de altura; dados redundantes se recolhem quando a prévia é curta, preservando contador e mensagens relevantes. Leituras de histograma/áudio continuam no Monitor Pro. Biblioteca e player permitem rolagem de ações em áreas menores. Veja [DESIGN.md](../development/DESIGN.md).

A gravação deixa de enviar a origem variável do timestamp da câmera ao gravador: apresentação/pacing usam System.nanoTime monotônico, e o timestamp da câmera serve à deduplicação. O contador consulta o início confirmado do gravador. Frames perdidos mantêm gaps reais. Ao parar, o MediaRecorder finaliza antes da liberação da superfície; amostras válidas são preservadas se houver falha. Duração/primeira amostra são verificadas antes de publicar. A biblioteca lê metadados fora da UI quando MediaStore ainda não tem duração; duração ausente aparece como —:—. Veja [DURACAO_E_GRAVACAO.md](../development/DURACAO_E_GRAVACAO.md).

O player avança por alternativas internas limitadas: SurfaceView/Media3, TextureView/Media3 síncrono e TextureView/MediaPlayer Android. Preserva o progresso recente após erro, respeita interrupções de áudio durante arraste e permite tempo de recuperação da superfície após retomada. O caminho nativo usa prepareAsync, callbacks por identidade/geração e seeks serializados. O workaround de edit lists fica no caminho compatível e pode alterar alinhamento legítimo de áudio/tempo. O diagnóstico registra o backend e eventos reais. Veja [REPRODUCAO.md](../guides/REPRODUCAO.md).

Essas correções não regravam clipes antigos com timestamps inválidos. A causa específica do vídeo travado relatado não foi reproduzida com o arquivo original; novos clipes e reprodução precisam ser conferidos no aparelho. Não se afirma correção universal.

## Artefatos

APK: `dist/LumaCamera-0.10-arm64-v8a-debug.apk`, **64.271.998 bytes** (~64,3 MB).

```text
SHA-256: 67B98B853CD5AC3A624065E826F02B6D5D4EBEDC1D2A9183962FC8E07860084D
Certificado SHA-256: 7b0bdcb9a3d294968f3a51b824876050df617afa4bf7560565ec1bf3c34046b2
```

Fonte: `dist/LumaCamera-projeto-0.10.zip`, com fontes, testes, modelos, licenças, scripts e documentação. Exclui builds, dist, caches, local.properties e chaves. APK/ZIP têm arquivos SHA-256 ao lado. A assinatura permite atualizar versões anteriores com o mesmo certificado; é de desenvolvimento.

MagicTouch v1 preservado: 6.227.884 bytes, SHA-256 `E24338A717C1B7AD8D159666677EF400BABB7F33B8AD60C4D96DB4ECF694CD25`, Apache 2.0 com atribuição.

Relatórios em dist: `TEST-*.xml`, `tests-and-lint-0.10.json`, `lint-results-debug.html`, `shader-verification.json`, `capture-compatibility-0.10.json`, `apk-assets-0.10.json`, `build-inputs-0.10.json`, `release-verification-0.10.json` e `source-snapshot-0.10.json`.

Avisos lint: SetTextI18n (8), GradleDependency (3), UseSwitchCompatOrMaterialCode (2), e um de cada ChromeOsAbiSupport, ClickableViewAccessibility, DataExtractionRules, Recycle, UnusedAttribute e ViewConstructor. O stream apontado por Recycle é fechado por bufferedWriter.use; inspeção do código não revelou um recurso aberto nesse caminho.

## Conferir no A14

1. Atualize por cima. Grave um **clipe novo de aproximadamente 10 segundos** sem efeitos, com áudio, nas duas câmeras e em vertical/horizontal. Compare contador, duração da biblioteca e começo/meio/fim no player interno. Repita sem áudio e com efeitos.
2. Teste pausa, arraste, ±5s, rever e retorno ao app. Desconecte fones ou interrompa áudio durante o arraste; soltar a barra deve respeitar a interrupção. Se travar, tente o modo compatível e salve **⋮ → Salvar diagnóstico de reprodução**.
3. Confira fonte normal/ampliada, retrato/paisagem, barras por gestos/botões, atalhos de galeria/inversão/menus e painel. Alterne Log/assistência, foco, medidor e monitores: controles dependentes devem reagir na hora. Alterar um ajuste não deve perder a posição de rolagem.
4. Ligue monitor/desfoque/estabilização de forma individual e conjunta, conferindo que guias/indicadores não entram no MP4 e comparando o material com tudo desligado.
5. Compare clipes antigos e novos. Um antigo com duração inválida pode permanecer problemático; preserve o original e seu diagnóstico. Meça aquecimento, FPS efetivo e sincronismo em clipes longos antes de usar a versão em trabalho importante.

Histórico: [0.9](VALIDACAO_0.9.md), [0.8](VALIDACAO_0.8.md), [0.7](VALIDACAO_0.7.md), [0.6](VALIDACAO_0.6.md), [0.5](VALIDACAO_0.5.md), [0.4](VALIDACAO_0.4.md). Fontes primárias: [REFERENCIAS.md](../development/REFERENCIAS.md).
