# Validação do Luma Camera 0.12

Data: **01/10/2026**. Versão **0.12.0-pro-tools**, código 12. APK de desenvolvimento **arm64-v8a**, Android 10 ou superior. **Uso diário no Galaxy A14 5G ainda não validado.**

## Evidência local

| Verificação | Resultado |
| --- | --- |
| Compilação integrada ARM64 | BUILD SUCCESSFUL em 1 min 34 s |
| JUnit | **302 testes em 39 suítes**; zero falhas, erros ou ignorados |
| Lint | Zero erros; **22 avisos** |
| GLSL/LUT | **96 contratos aprovados** em WebGL/ANGLE |
| Continuidade | **16 arquivos protegidos idênticos à fonte 0.11**, incluindo player, relógio, validação, Log e políticas de máscaras/estabilização |
| Assets no APK | Os 10 assets próprios correspondem à fonte |
| Fonte compilada | 124 entradas acompanhadas; nenhuma mudou após o início da compilação final |
| Assinatura | apksigner aprovado; certificado de desenvolvimento preservado |
| Manifesto/ABI | Código 12, mínimo 29/alvo 34, somente arm64-v8a; sem INTERNET/ACCESS_NETWORK_STATE |
| Execução em Android/A14 | **Pendente**; nenhum aparelho conectado no ADB |

Comandos: scripts/Verify.ps1 -Abi arm64-v8a e node scripts/VerifyShaders.cjs. JDK 17, SDK/Build Tools 34, Gradle 8.5, AGP 8.2.2 e Kotlin 1.9.22. Dependências preservadas: Media3 1.4.1, ML Kit 16.0.0-beta6 e MediaPipe Tasks Vision 0.10.14.

São 54 testes adicionais em relação à 0.11. Cobrem novos contratos de LUT, scopes, geometria do retículo, políticas de acompanhamento, foco/zoom A/B, opções de gravação, nomes das tomadas, roteamento de áudio e normalização de preferências. A primeira tentativa integrada encontrou um erro de compilação de teste Kotlin; o teste passou a guardar prediction.point numa variável local. A compilação e todos os testes finais passaram depois dessa correção.

Os testes gráficos incluem separação entre prévia e encoder: uma LUT da prévia não altera os pixels destinados ao arquivo. O ensaio usa WebGL/ANGLE e substitui sampler externo por textura 2D; **não valida EGL, SurfaceTexture ou samplerExternalOES no Android**.

## Recursos entregues

A [documentação de ferramentas](../guides/FERRAMENTAS_PRO_0.12.md) descreve presets, LUTs de prévia, waveform/RGB parade/vectorscope, movimentos de zoom e foco A/B, microfone externo, projetos/cenas/tomadas e gravação em partes. A central Ferramentas reúne busca, categorias e três atalhos personalizáveis, abrindo a seção original de cada ajuste.

Travar foco e acompanhar por IA são controles independentes. Acompanhamento pode continuar mostrando o alvo com a lente travada/manual. Em foco automático, a área física só acompanha quando a câmera oferece regiões AF. A confirmação da trava depende dos resultados Camera2.

A troca de partes usa o mesmo MediaRecorder. Arquivos ativos não são lidos, recuperados ou publicados durante REC. Após STOP, os originais são preservados e as partes validadas seguem uma fila de publicação separada. A rota de áudio real é consultada durante REC; um dispositivo preferido não é apresentado como confirmado antes disso.

Os backends do player e as políticas de tempo/validação existentes foram preservados. A biblioteca ganhou identificação e filtro por projeto, além da exportação da ficha JSON de cada tomada. Nenhuma dessas alterações comprova que uma falha de driver observada no A14 esteja resolvida sem novo teste físico.

## Limites e próxima validação

- Foco A/B exige controle manual real da lente. A disponibilidade anunciada ainda precisa ser conferida no A14, em ambas as câmeras.
- IA trabalha com máscaras e correspondência visual local; não mede profundidade nem reconhece identidade persistente. Perda, ambiguidade ou oclusão podem exigir novo toque.
- Scopes usam leitura reduzida 64×36 a até 4 Hz do sinal destinado ao encoder. A base continua SDR de 8 bits, com Log simulado; não acrescenta dados RAW, realces perdidos ou faixa dinâmica.
- Microfones USB/com fio dependem das entradas e rotas disponibilizadas pelo Android. IDs pertencem à conexão atual, por isso a preferência volta para Automática ao abrir o app.
- Continuidade, duração e áudio entre partes ainda não foram medidos em gravação real. Encerramento abrupto pode deixar a última parte incompleta; preservação não repara automaticamente seu índice.
- Nenhum ensaio local mede FPS, latência, temperatura, consumo, sincronismo de áudio ou disposição visual nativa. Compilação, JVM e WebGL não substituem os drivers do aparelho.

O [roteiro físico no A14](../validation/TESTE_DIARIO_A14.md) mantém os resultados pendentes. Comece com um clipe novo de 10 segundos sem efeitos; confira proporção, orientação, áudio, duração e player. Depois teste recursos individualmente, incluindo três partes de 64 MiB, desconexão do microfone e perda/reseleção do alvo.

## Artefatos e integridade

- APK: dist/LumaCamera-0.12-arm64-v8a-debug.apk — **64.571.410 bytes**.
- SHA-256 do APK: **A21EAE9BC44D7C2156A98C61306201CBBDFFB9F334D6F71F4DB1B9E821139CAC**.
- Certificado SHA-256: **7b0bdcb9a3d294968f3a51b824876050df617afa4bf7560565ec1bf3c34046b2**. A identidade de desenvolvimento permanece compatível com as versões anteriores assinadas pela mesma chave.
- Projeto: dist/LumaCamera-projeto-0.12.zip. O pacote inclui fonte, assets/modelos/licenças, Gradle wrapper, scripts e documentação; exclui APKs, builds, caches e local.properties.
- Hashes dos artefatos ficam nos arquivos .sha256 correspondentes. A fonte extraída do ZIP é comparada, entrada por entrada, com o workspace.

Relatórios em dist: tests-and-lint-0.12.json, build-inputs-0.12.json, shader-verification.json, apk-assets-0.12.json, apk-identity-0.12.json, capture-compatibility-0.12.json, source-snapshot-0.12.json, release-verification-0.12.json e lint-results-debug.html.

Histórico: [validação 0.11](VALIDACAO_0.11.md) e [auditoria 0.11](AUDITORIA_ESTABILIDADE_0.11.md). A 0.12 implementa as ferramentas solicitadas, mantendo a promoção para uma versão estável dependente do roteiro físico.

