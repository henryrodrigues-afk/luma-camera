# Validação do Luma Camera 0.11

Data: **01/10/2026**. Versão **0.11.0-stability**, código 11. Candidata de estabilidade, APK **arm64-v8a**, Android 10 ou superior. **Uso diário no Galaxy A14 5G ainda não validado.**

## Evidência local

| Verificação | Resultado |
| --- | --- |
| Compilação integrada ARM64 | BUILD SUCCESSFUL em 2 min 49 s |
| JUnit | **248 testes em 29 suítes**; zero falhas, erros ou ignorados |
| Lint | Zero erros; **21 avisos** |
| GLSL/LUT | **83 contratos aprovados** em WebGL/ANGLE |
| Continuidade | **23 arquivos protegidos idênticos à fonte 0.10**, incluindo geometria, Log/LUTs, shaders, modelos/licenças, máscaras e estabilização |
| Assets no APK | Os 10 assets próprios correspondem à fonte |
| Fonte verificada | 96 entradas acompanhadas; nenhuma mudou após o início da compilação |
| Assinatura | apksigner aprovado; certificado de desenvolvimento preservado |
| Manifesto/ABI | Código 11, mínimo 29/alvo 34, somente arm64-v8a; câmera, microfone e receiver interno; sem INTERNET/ACCESS_NETWORK_STATE |
| Execução em Android/A14 | **Pendente**; nenhum aparelho conectado no ADB |

Comandos: `scripts/Verify.ps1 -Abi arm64-v8a` e `node scripts/VerifyShaders.cjs`. JDK 17, SDK/Build Tools 34, Gradle 8.5, AGP 8.2.2, Kotlin 1.9.22. Dependências preservadas: Media3 1.4.1, ML Kit 16.0.0-beta6 e MediaPipe Tasks Vision 0.10.14.

Os 24 testes novos cobrem reserva/cópias/limite de armazenamento, preservação de não vazios, bateria/temperatura/dados ausentes, identidade de reabertura, retenção da tela e tickets de operações da biblioteca. A revisão entre agentes verificou os deltas de captura/Store e UI/player. Compilações isoladas passaram antes da integração.

## Resultado da auditoria

A [auditoria completa](AUDITORIA_ESTABILIDADE_0.11.md) registra achados P1/P2, mudanças implementadas, recursos propostos e critérios de promoção para estável. O [roteiro no A14](../validation/TESTE_DIARIO_A14.md) mantém os resultados físicos pendentes.

Captura/armazenamento: espaço acompanhado durante REC com margem para original/cópia/finalização; arquivo exclusivo reservado atomicamente; originais não vazios preservados em erro de parser/finalização; publicação protegida contra concorrência, cópia incompleta e rollback depois de commit. Arquivos ativos não são oferecidos para recuperação. Originais incertos aparecem como não validados, sem duração inventada ou autoplay.

UI/lifecycle: pedidos de REC/STOP e microfone não duplicam; reabertura não fecha uma sessão igual já ativa; menus/permissões/áudio refletem o estado atual; rejeição física antiga não substitui edição recente ou efeitos locais. O novo painel Condições para gravar reúne leituras pontuais em thread separada, sem alterar qualidade.

Player/biblioteca: retenção de tela desliga ao concluir/interromper; tickets invalidam compartilhamento tardio; compartilhar durante publicação espera seu fim; cópias/exportações autorizadas podem terminar em rotação sem reabrir a UI anterior. Os backends internos da 0.10 permanecem. GPU agrupa renders pendentes e invalida o handle do encoder mesmo em falha de detach.

## Limites reais

Os testes JVM não executam MediaRecorder, MediaStore, Camera2, EGL/OES, AudioTrack, codecs Samsung ou views/inferência Android. WebGL usa os GLSL atuais com pixels sintéticos e substitui o sampler OES por textura 2D. Não foi medido FPS, latência de máscaras, memória, aquecimento, bateria, sincronismo audiovisual ou estabilidade visual nativa.

Preservar bytes não repara automaticamente um MP4 sem índice ou com timestamps antigos inválidos. Consumo abrupto de espaço por outro app pode vencer o watchdog/margem. Estado térmico depende do sistema e temperatura exibida é da bateria. A estimativa de tempo usa bitrate alvo, não VBR real. Gate de publicação é por processo, sem journal persistente de crash. R8/release e assinatura de lançamento ainda exigem validação própria.

Não há promessa de correção universal ou de versão estável já aprovada para trabalho diário. Waveform, RGB parade, vetorscope, presets, rack focus e tracking avançado continuam no backlog da auditoria, sem serem anunciados como implementados.

## Artefatos

APK: `dist/LumaCamera-0.11-arm64-v8a-debug.apk`, **64.312.966 bytes** (~64,3 MB).

```text
SHA-256: E8D21556234A047D7FDD487F286B52ED28F8D785E00EC53C6D974132F4F5A7EB
Certificado SHA-256: 7b0bdcb9a3d294968f3a51b824876050df617afa4bf7560565ec1bf3c34046b2
```

Fonte: `dist/LumaCamera-projeto-0.11.zip`. Inclui fontes, testes, modelos/licenças, scripts, auditoria e roteiro. Exclui builds, dist, caches, local.properties e chaves. APK/ZIP têm SHA-256 ao lado. Assinatura de desenvolvimento permite atualizar as versões anteriores com esse certificado.

MagicTouch v1: 6.227.884 bytes, SHA-256 `E24338A717C1B7AD8D159666677EF400BABB7F33B8AD60C4D96DB4ECF694CD25`, Apache 2.0 com atribuição, sem modificação.

Relatórios em dist: TEST-*.xml, tests-and-lint-0.11.json, lint-results-debug.html, shader-verification.json, capture-compatibility-0.11.json, apk-assets-0.11.json, apk-identity-0.11.json, build-inputs-0.11.json, release-verification-0.11.json e source-snapshot-0.11.json.

Avisos lint: SetTextI18n (10), GradleDependency (3), UseSwitchCompatOrMaterialCode (2), e um de cada ChromeOsAbiSupport, ClickableViewAccessibility, DataExtractionRules, Recycle, UnusedAttribute e ViewConstructor. Os dois avisos novos são SetTextI18n; não foram acrescentados erros ou avisos de APIs incompatíveis. Recycle aponta o exportador de LUT, fechado por bufferedWriter.use. Acessibilidade e strings em recursos permanecem itens de revisão de distribuição.

Instale por cima e percorra [TESTE_DIARIO_A14.md](../validation/TESTE_DIARIO_A14.md), começando por clipes novos de 10 segundos. Histórico: [0.10](VALIDACAO_0.10.md), [0.9](VALIDACAO_0.9.md), [0.8](VALIDACAO_0.8.md), [0.7](VALIDACAO_0.7.md), [0.6](VALIDACAO_0.6.md), [0.5](VALIDACAO_0.5.md), [0.4](VALIDACAO_0.4.md).
