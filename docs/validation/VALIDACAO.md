# Validação do Luma Camera 1.0

Data: **01/10/2026**. Versão **1.0.0**, código **100**, mínimo **API 29**, alvo/compilação **API 34**. A revisão final, incluindo ajustes automáticos de desfoque/exposição, passou na integração abaixo. O APK final também gravou e reproduziu um novo clipe no A14, com áudio presente, tempos crescentes e decodificação integral aprovada. Os ensaios do APK anterior estão identificados como baseline. Uso diário completo, qualidade visual da IA, microfone externo e estabilidade térmica não foram aprovados.

Os [resultados agregados em JSON](RESULTADOS_1.0.json) acompanham este registro público. Não incluem mídia pessoal, nomes de arquivos capturados, telas, IPs, números de série ou logs brutos do aparelho.

## Verificações da fonte final

| Verificação | Resultado | Evidência |
| --- | --- | --- |
| Build ARM64 debug e release | **BUILD SUCCESSFUL em 5 min 1 s** | `dist/reports/1.0/build-final-1.0.log` |
| Entradas congeladas do build | **Todos os bytes permaneceram iguais** durante a compilação | `dist/reports/1.0/build-inputs-1.0.json`; conferência final coordenada da fonte Android/recursos/build |
| JUnit integrado | **383 testes em 49 suítes; 0 falhas, 0 erros, 0 ignorados** | Soma dos atributos e dos 383 `testcase` dos XML em `dist/reports/1.0/tests/`; sem divergência por suíte |
| Lint debug e release | **0 erros/fatais; 23 avisos em cada variante** | XML/HTML `dist/reports/1.0/raw/lint-results-debug.*` e `lint-results-release.*` |
| Contratos gráficos no host | **117 aprovados em WebGL/ANGLE**, incluindo 11 novos de blur/máscaras | `dist/reports/1.0/shader-verification.json`; `androidEglVerified=false`, `androidExternalOesVerified=false` |
| Hash/tamanho dos APKs finais | **Conferidos** após copiar para a entrega | Valores abaixo, também no agregado JSON |
| Assinatura | **apksigner aprovado, assinatura v2 e certificado anterior preservado** | SHA-256 público do certificado abaixo |
| Manifesto, ABI e assets do APK final | **Aprovados nos dois APKs finais**: ARM64, versão 1.0.0/100, mínimo 29/alvo 34, 13 assets próprios idênticos à fonte, sem INTERNET/ACCESS_NETWORK_STATE | `dist/reports/1.0/apk-inspection.json`; hashes/tamanhos finais conferidos e sem mudança durante a inspeção; release não depurável |
| Aparelho no APK final | **Atualização, ajustes automáticos, captura, áudio e fim de reprodução observados** | Tomada frontal de 25,216411 s; decodificação integral aprovada e idade captura→máscara agregada abaixo |

As suítes isoladas se sobrepõem e **não são somadas**. A frente de player/biblioteca passou em 42 testes isolados; a preparação de captura/estilos/exposição em 59; a suíte final de efeitos em 110. O total consolidado é **383**, dos 49 XML da execução integrada final. Kotlin/SDK 34 e ML Kit foram compilados, mas testes JVM não executam câmera, codecs, modelo ou driver EGL do Android. Os resultados da [0.13](../history/VALIDACAO_0.13.md) pertencem àquela compilação.

Os avisos do lint são: `SetTextI18n` (12), `GradleDependency` (3), `UseSwitchCompatOrMaterialCode` (2) e um de cada `ChromeOsAbiSupport`, `ClickableViewAccessibility`, `DataExtractionRules`, `Recycle`, `UnusedAttribute` e `ViewConstructor`. Zero erros do lint não significa ausência de riscos no aparelho.

## Baseline físico no Galaxy A14 5G

Aparelho **Samsung Galaxy A14 5G, SM-A146M, Android 15**. Esses ensaios precedem o ajuste final de desfoque/exposição e correspondem à release de SHA-256 **`AD1347DE3BC831A23B8A43A71AB1F596BCCACE2AC73481B5BC70C2183317A482`**, que foi atualizada preservando assinatura e originais. São evidência da base de captura/reprodução exercitada, sem promover automaticamente a revisão final da IA.

| Ensaio anterior | Resultado observado | Alcance |
| --- | --- | --- |
| Player interno com arquivos sintéticos | Clipes de **3 s e 20 s** chegaram ao fim na interface Android | Caminho exercitado; não força todos os backends/erros |
| Controles do player | Pausa aproximadamente em **1 s**, avanço **+5 s** para cerca de **6 s**, mudo/reativação e aviso correto de volume zero | Fones, foco durante arraste e sincronismo acústico permanecem pendentes |
| Captura traseira Log v2 | **1080 × 1920, 10,975956 s, 329 quadros de vídeo**; AAC **44.100 Hz mono, 471 quadros** | Tomada curta; demais modos/orientações e aparência Log pendentes |
| Timeline traseira | PTS/DTS crescentes por trilha, zero duplicados/regressivos; diferenças A/V de início **6,893 ms** e fim aproximadamente **33 ms** | Timestamps não medem sincronismo perceptivo |
| Captura frontal | **H.264, 1080 × 1920, 9,004333 s**, média aproximadamente **29,986 FPS** | Não mede FPS sustentado ou todas as orientações |
| Áudio frontal | AAC **44.100 Hz mono, 8,939320 s**, início **22,404 ms**; não silencioso, média **−34,5 dB**, máximo **−18,1 dB** no áudio decodificado | Estatística de arquivo; sem calibração do microfone |
| Player com tomada frontal | Fim de reprodução, **9 s / 9 s**, **Rever**, sem erro observado | Confirma aquele arquivo/caminho de reprodução |
| Sessão em partes | Sessão encerrada com **3 partes salvas**; durações de vídeo **58,059878 / 57,632089 / 12,654354 s**; **1080 × 1920**, AAC **44.100 Hz mono** | Não comprova junção sem lacuna, parada exatamente na troca ou rota externa |
| Timeline das três partes | Vídeo começou aproximadamente **2,3 / 89,2 / 61,6 ms** depois do áudio, respectivamente | Não comprova sincronismo acústico nem continuidade entre arquivos |
| Inspeção conjunta de cinco MP4 | **4.375 pacotes de vídeo + 6.382 AAC**, zero PTS/DTS repetidos/regressivos; decodificação integral com base de tempo do demuxer/passthrough, saída **0**, sem erros/avisos | Host; reprodução Android de todas as partes e interrupções continuam pendentes |
| Amostra de realces | Amostra de **2 fps**: picos Y **213–218**; após LUT v2/100% em **320 × 180**, picos RGB **227–237**, nenhum valor ≥250 observado | Amostragem/subescala pode omitir brilhos pequenos; não comprova ausência de clipping no SDR/sensor |
| Interface em retrato | Área de vídeo/controles sem sobreposição observada; prévia frontal espelhada intencionalmente | Fontes grandes, paisagem e geometria formal com marca assimétrica pendentes |

## Reteste do APK final

Release: **`F22AF9F5EEC2B787C94470CD0B0DD23872EB0D704A8FF8AFA644760434071A65`**. A atualização assinada foi instalada com sucesso no A14. O celular estava carregando, apontado para o teto, **sem pessoa na cena**. Esses ensaios não aprovam a aparência do recorte em cabelo ou movimento.

| Ensaio final | Resultado observado | Alcance |
| --- | --- | --- |
| Preparação de Pessoas | Natural aplicado com intensidade **22%**, estabilidade **42%**, bordas **20%**, análise Leve e acompanhamento ativo; refinamentos acessíveis | Configuração/execução; não havia pessoa para avaliar o recorte |
| Preparação de Objetos | Natural ficou aguardando seleção, com instrução para tocar no alvo | Seleção explícita preservada; não testa seguir um objeto |
| Exposição automática | **EV aproximadamente −0,3**, ISO/obturador automáticos e trava AE liberada | Estado aplicado naquela lente; não mede resposta a mudanças da iluminação |
| Captura frontal final | MP4 de **25,216411 s**; vídeo **H.264, 1080 × 1920, 25,202311 s, 753 quadros**, média **29,878 FPS**; LumaLog v2/100%, Pessoas Natural/IA e estabilização ativos | Uma parte salva; tomada curta, sem pessoa |
| Áudio | AAC **44.100 Hz mono, 1.085 pacotes**, **1.111.040 amostras decodificadas**; sinal presente, média **−38,5 dBFS**, pico **−20,3 dBFS** | Áudio ambiente; escuta humana, calibração e sincronismo acústico pendentes |
| Timeline | PTS/DTS e PTS de quadros estritamente crescentes, zero repetidos/regressivos; maior intervalo observado **79,589 ms** | Não comprova cadência constante nem sincronismo acústico |
| Decodificação integral | Base de tempo do demuxer e passthrough, saída **0**, zero diagnósticos | Host; não substitui reprodução de todos os modos/decoders Android |
| Player interno final | Chegou a **0:25 / 0:25**, apresentou **Cena concluída · toque em Rever**, com **Rever** disponível e som habilitado | Aquele arquivo/caminho; todos os fallbacks e interrupções continuam pendentes |
| Idade captura→retorno da máscara | **80 janelas, 5.909 análises**, média ponderada aproximada **60,09 ms**, máximo **1.265 ms**, **2 resultados expirados**; zero entradas `AndroidRuntime` observadas | Agregado de logs do processo, não só do clipe; média a partir de valores inteiros por janela, inclui preparo/inferência e não mede atraso visual completo |

O app foi enviado à tela inicial após o ensaio. Os dois resultados expirados foram reduzidos/descartados conforme o orçamento de idade; a média não garante latência por quadro. **Qualidade visual de cabelo, contorno, movimento, foco e seguimento continua pendente:** não havia pessoa ou objeto selecionado na cena.

## Matriz de critérios e regressões

| Área | Critério verificado no código/teste | Referências | Limite da evidência |
| --- | --- | --- | --- |
| Áudio durante busca | Ganho de foco/superfície não inicia o player nativo enquanto há arraste ou busca pendente; pausa e perda de foco continuam prevalecendo | [VideoPlaybackPolicy.kt](../../app/src/main/java/com/lumacamera/core/VideoPlaybackPolicy.kt), [testes](../../app/src/test/java/com/lumacamera/core/VideoPlaybackPolicyTest.kt), [VideoPlayerView.kt](../../app/src/main/java/com/lumacamera/ui/VideoPlayerView.kt) | Política JVM e revisão de integração; callbacks reais do Android pendentes |
| Posição e falhas do player | Busca recusada preserva o destino; getters inválidos não substituem progresso saudável; metadados antigos não são aplicados | [VideoPlayerView.kt](../../app/src/main/java/com/lumacamera/ui/VideoPlayerView.kt), [testes de playback](../../app/src/test/java/com/lumacamera/core/VideoPlaybackPolicyTest.kt) | Ensaio básico do player aprovado; não repara clipe legado nem valida todos os decoders/fallbacks |
| Identidade após publicar | URI exata tem prioridade; nome único permite restauração privada→galeria; duplicidade não produz seleção arbitrária | [LibraryActionPolicy.kt](../../app/src/main/java/com/lumacamera/core/LibraryActionPolicy.kt), [testes](../../app/src/test/java/com/lumacamera/core/LibraryActionPolicyTest.kt) | A política passou; rotação durante cópia real continua pendente |
| Concorrência da biblioteca | Tickets antigos não abrem chooser após pausa/navegação/atualização; alterações da galeria podem solicitar uma nova leitura sem paralelizar o worker | [LibraryActivity.kt](../../app/src/main/java/com/lumacamera/LibraryActivity.kt), [testes de tickets](../../app/src/test/java/com/lumacamera/core/LibraryActionPolicyTest.kt) | Permissão de leitura e ciclo de vida do chooser precisam do aparelho |
| Layout | Conteúdo auxiliar da biblioteca rola com a lista; etiquetas se empilham; controles não excedem a altura disponível | [LibraryActivity.kt](../../app/src/main/java/com/lumacamera/LibraryActivity.kt), [ResponsiveUiPolicyTest.kt](../../app/src/test/java/com/lumacamera/core/ResponsiveUiPolicyTest.kt) | Interface normal exercitada; fontes ampliadas/janelas reduzidas continuam pendentes |
| Presets | Novo nome no limite não apaga dados; substituição no limite continua possível | [WorkspacePolicy.kt](../../app/src/main/java/com/lumacamera/core/WorkspacePolicy.kt), [testes](../../app/src/test/java/com/lumacamera/core/WorkspacePolicyTest.kt), [ProfessionalWorkspaceStore.kt](../../app/src/main/java/com/lumacamera/ui/ProfessionalWorkspaceStore.kt) | Verificação da persistência/menus no A14 pendente |
| Orçamento de espaço | Cópias pendentes não consomem o limite do arquivo atual; cópia deixa reserva de finalização; valores grandes saturam com segurança | [StoragePolicy.kt](../../app/src/main/java/com/lumacamera/core/StoragePolicy.kt), [testes](../../app/src/test/java/com/lumacamera/core/StoragePolicyTest.kt) | Espaço efetivo/MediaStore/concorrência nativos pendentes |
| Cópia e publicação | Apenas bytes escritos liberam reserva; transferência exata recusa origem alterada/falha; original não vazio é preservado | [RecordingCopy.kt](../../app/src/main/java/com/lumacamera/core/RecordingCopy.kt), [testes](../../app/src/test/java/com/lumacamera/core/RecordingCopyTest.kt), [VideoStore.kt](../../app/src/main/java/com/lumacamera/camera/VideoStore.kt) | Teste de interrupção/baixo espaço real pendente |
| Partes e áudio | Evento de troca no worker é tratado sem segundo post; troca não confirmada deixa estimativa desconhecida; ficha registra rota observada por parte | [CameraEngine.kt](../../app/src/main/java/com/lumacamera/camera/CameraEngine.kt), [RecordingOptions.kt](../../app/src/main/java/com/lumacamera/core/RecordingOptions.kt), [testes](../../app/src/test/java/com/lumacamera/core/RecordingOptionsTest.kt) | `setNextOutputFile`, limites e rota física dependem do gravador do aparelho |
| Duração e recuperação | `elapsedEstimateMs`, quando disponível, participa tanto da classificação privada quanto da recuperação; valor estimado não substitui duração do MP4 | [VideoStore.kt](../../app/src/main/java/com/lumacamera/camera/VideoStore.kt), [RecordingValidation.kt](../../app/src/main/java/com/lumacamera/core/RecordingValidation.kt), [testes](../../app/src/test/java/com/lumacamera/core/RecordingValidationTest.kt) | Estimativa ausente não prova compatibilidade com tempo de relógio; validar arquivo real |
| Falha temporária de sondagem | Sondagem nativa incompleta pode tentar novamente após 10 s numa atualização; uma timeline conhecida como inválida permanece rejeitada | [RecordingProbePolicy.kt](../../app/src/main/java/com/lumacamera/core/RecordingProbePolicy.kt), [testes](../../app/src/test/java/com/lumacamera/core/RecordingProbePolicyTest.kt) | Política passou; falhas de parser reais e resposta da galeria pendentes |
| Identidade do alvo IA | Centro permanece no componente conectado escolhido; referência visual exige apoio da máscara em todo o patch | [SubjectTrackAnalysisTest.kt](../../app/src/test/java/com/lumacamera/effects/SubjectTrackAnalysisTest.kt), [ObjectTrackingPolicyTest.kt](../../app/src/test/java/com/lumacamera/core/ObjectTrackingPolicyTest.kt) | Máscaras sintéticas não validam inferência nativa, latência ou cena real |
| Mudança de zoom | Configuração nova invalida referência/resultados anteriores de análise, preservando a correção exibida e sua liberação suave | [MotionAnalysisConfigurationPolicyTest.kt](../../app/src/test/java/com/lumacamera/effects/MotionAnalysisConfigurationPolicyTest.kt), [GpuPipeline.kt](../../app/src/main/java/com/lumacamera/effects/GpuPipeline.kt) | Recorte/transformação no driver Android e movimento real pendentes |
| Ajustes automáticos | Preparação de Pessoas/Objetos preserva lente/WB/Log/estabilização; estilos moderados e seleção explícita; preparação de AE respeita faixa/passo e não usa ganho digital | [CaptureSettingsPreparationTest.kt](../../app/src/test/java/com/lumacamera/core/CaptureSettingsPreparationTest.kt), [AutomaticBlurPolicyTest.kt](../../app/src/test/java/com/lumacamera/core/AutomaticBlurPolicyTest.kt), [ExposureSafetyPolicyTest.kt](../../app/src/test/java/com/lumacamera/core/ExposureSafetyPolicyTest.kt) | Políticas integradas e ações Natural/AE observadas no APK final; qualidade do recorte pendente |
| Idade e movimento da máscara | Máscara nativa alinhada nas rotações; bytes de análise próprios; expiração desde a captura; bordas em movimento atualizam mesmo com centro global fixo | [PortraitPixelsTest.kt](../../app/src/test/java/com/lumacamera/effects/PortraitPixelsTest.kt), [PortraitMaskPolicyTest.kt](../../app/src/test/java/com/lumacamera/effects/PortraitMaskPolicyTest.kt), [TemporalMaskPolicyTest.kt](../../app/src/test/java/com/lumacamera/effects/TemporalMaskPolicyTest.kt), [ObjectMaskPolicyTest.kt](../../app/src/test/java/com/lumacamera/core/ObjectMaskPolicyTest.kt) | Idade nativa agregada registrada sem pessoa; aparência e movimento reais do recorte pendentes |
| Blur ponderado | Cor do assunto excluída dos taps do fundo e inversão coerente; suporte insuficiente conserva original; oval/IA usam samplers e raios próprios | [VerifyShaders.cjs](../../scripts/verification/VerifyShaders.cjs), [blur.frag](../../app/src/main/assets/shaders/blur.frag), [effects.frag](../../app/src/main/assets/shaders/effects.frag) | 11 contratos gráficos novos no host; cabelo, movimento e GPU Android não são aprovados por pixels sintéticos |

## Revisão da integração captura → biblioteca → player

Revisão somente de leitura sobre as fontes atualizadas, sem nova alteração de produção:

- A captura reserva os nomes das partes e mantém os arquivos ativos/pendentes fora da listagem até a finalização ou conclusão da publicação. A reserva manual e a reserva de partes enfileiradas usam o mesmo `VideoStore`.
- A ficha preserva `elapsedEstimateMs` como estimativa de captura. A duração mostrada pelo player continua vindo do contêiner; a biblioteca não usa a ficha como duração de reprodução.
- `privateVideo` aplica a plausibilidade temporal e entrega `validated=false`/duração desconhecida quando reprova. `recover` reaplica a estimativa conhecida. A Library apresenta **NÃO VALIDADO**, permite inspeção/compartilhamento e desativa autoplay desses originais.
- O cache diferencia sondagem incompleta de timeline reprovada. A atualização da Library pode repetir uma falha temporária após 10 s sem reabrir continuamente um parser nativo; isso não transforma uma timeline inválida em válida.
- A cópia completa é publicada antes de remover o original. A mudança de URI conserva o nome, usado pela restauração da Library apenas quando não há ambiguidade.
- Player e compartilhamento usam `video/mp4`/URI com autorização temporária de leitura; isso corresponde ao formato produzido e listado pelo app. A ficha JSON é exportada separadamente.

Não foi identificado um contrato incompatível nesta revisão de fonte. Os ensaios físicos do baseline estão registrados separadamente acima; a revisão não comprova todos os cenários de finalização, indexação, URI, recuperação ou reprodução.

## Configuração e artefatos

A [configuração Gradle](../../app/build.gradle.kts) declara mínimo 29/alvo 34, versão `1.0.0`/`100` e ABI opcional por `lumaAbi`. A inspeção dos dois APKs finais confirmou apenas **arm64-v8a**, versão/minSDK/alvo corretos, ausência de INTERNET/ACCESS_NETWORK_STATE e **13 assets próprios idênticos à fonte**. Relatórios locais de `dist/` não são links de download: essa pasta é ignorada pelo Git.

`isMinifyEnabled=false`: redução de código da release desativada para preservar pontos de entrada JNI/modelos. Isso não mede memória/desempenho nem valida inferência nativa. Assinatura release própria exige o conjunto completo `LUMA_SIGNING_STORE`, `LUMA_SIGNING_STORE_PASSWORD`, `LUMA_SIGNING_KEY_ALIAS` e `LUMA_SIGNING_KEY_PASSWORD`; sem ele, a saída é não assinada. Chaves e senhas ficam fora da fonte e dos relatórios.

| APK final | Tamanho | SHA-256 |
| --- | --- | --- |
| [Release ARM64](https://github.com/henryrodrigues-afk/luma-camera/releases/download/v1.0.0/LumaCamera-1.0-arm64-v8a.apk) | **60.972.667 bytes** | `F22AF9F5EEC2B787C94470CD0B0DD23872EB0D704A8FF8AFA644760434071A65` |
| [Debug ARM64](https://github.com/henryrodrigues-afk/luma-camera/releases/download/v1.0.0/LumaCamera-1.0-arm64-v8a-debug.apk) | **64.847.291 bytes** | `7F7778A4788F8F2B7B5E00D56C3C9C6643B4302387E309C8DD998CE08AD439E3` |

Certificado SHA-256 preservado: `7b0bdcb9a3d294968f3a51b824876050df617afa4bf7560565ec1bf3c34046b2`. O snapshot acompanha a Release como [LumaCamera-1.0-source.zip](https://github.com/henryrodrigues-afk/luma-camera/releases/download/v1.0.0/LumaCamera-1.0-source.zip); seu hash fica no [SHA256SUMS.txt externo](https://github.com/henryrodrigues-afk/luma-camera/releases/download/v1.0.0/SHA256SUMS.txt). O hash do ZIP não é incluído dentro da própria fonte.

## Percursos físicos ainda pendentes

O [roteiro diário](TESTE_DIARIO_A14.md) continua aplicável. Os ensaios curtos acima não substituem estes percursos completos.

| Cenário | Estado/limite |
| --- | --- |
| Captura/player com os novos ajustes automáticos | Uma tomada frontal final aprovada para arquivo/áudio/EOS; outras lentes, orientações, perfis e repetições pendentes |
| Recorte de pessoas/objetos, atraso e aparência | Pendente: cabelos/mãos/movimento/oclusão, dois sujeitos, objeto liso/fino, perda e nova seleção; sem assumir identidade ou profundidade |
| Lifecycle e fallback | Rotação, saída/retorno, bloqueio, perda de áudio/fones e interrupção durante arraste pendentes |
| Partes automáticas | Baseline de 3 partes aprovado para timeline/decodificação; troca interrompida, continuidade, reprodução de todas as partes e sessão extensa pendentes |
| Publicação/recuperação e espaço baixo | Rotação durante cópia, recuperação, compartilhamento com nova URI, pressão de espaço e falhas reais pendentes |
| Foco/zoom/estabilização | Trava, regiões AF, A/B, zoom variável, perda/retomada e cantos no driver Android pendentes |
| Log/monitores/LUT | Baseline com Log presente; aparência, LUT, indicador só na tela e realces na revisão final pendentes |
| Layout | Retrato normal observado no baseline; paisagem, fontes grandes, janela estreita e telas divididas pendentes |
| Áudio externo/uso diário | Microfone USB/com fio, rotas/interrupções, temperatura, bateria, FPS sustentado e sessões longas pendentes |

## Limitações

H.264 SDR de 8 bits/AAC opcional e controles de lente dependem do aparelho. LumaLog simulado não recupera realces cortados nem acrescenta RAW, 10 bits ou faixa dinâmica. LUT e monitores são de prévia.

IA/desfoque/acompanhamento permanecem experimentais. Máscaras são locais, não mapas de profundidade; não há fluxo óptico ou reprojeção da máscara. Pouca textura, objetos finos/lisos/transparentes, oclusões e movimento rápido podem perder a seleção. Pessoas reduzem contribuição após 250 ms e expiram aos 600 ms; objetos após 450/1.200 ms, desde a captura. Inferência lenta pode reduzir ou eliminar temporariamente o efeito. Os contratos sintéticos não aprovam cabelo, qualidade de imagem ou latência real.

Estabilização usa recorte, reduz campo/detalhe e não corrige rolling shutter, borrão ou perspectiva. WebGL/ANGLE com textura 2D não valida EGL/SurfaceTexture/OES, precisão, codec ou custo da GPU Android; a captura nativa exerceu um caminho, sem medir todos esses contratos. Arquivos antigos com tempos inválidos são preservados, mas reprodução/recuperação não são garantidas. Pacotes/timestamps, realces amostrados, áudio decodificado e idade agregada da máscara não medem sincronismo acústico, clipping do sensor, bateria ou fluidez sustentada.
