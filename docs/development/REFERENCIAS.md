# Referências técnicas do Luma Camera

## Relógio, recuperação interna e janela — 0.10

- [AOSP: implementação de System.nanoTime](https://android.googlesource.com/platform/libcore/+/c232b509db9aa6d06634c7694729a66de46bc63a/ojluni/src/main/native/System.c): origem CLOCK_MONOTONIC usada pela timeline de gravação, em vez de assumir a origem de SurfaceTexture.
- [Android: SurfaceTexture.getTimestamp](https://developer.android.com/reference/android/graphics/SurfaceTexture#getTimestamp()): timestamp associado à imagem do produtor. A base usa o valor para deduplicação e não como relógio compartilhado com o áudio.
- [Media3: tipos de superfície](https://developer.android.com/media/media3/ui/surface) e [problemas de reprodução MP4/fMP4](https://developer.android.com/media/media3/exoplayer/troubleshooting#why-do-some-mp4fmp4-files-play-incorrectly): sustentam a alternativa TextureView e o workaround de edit lists, limitado à recuperação.
- [Android MediaPlayer](https://developer.android.com/reference/android/media/MediaPlayer): estados, prepareAsync, superfícies, seeks e callbacks do backend interno alternativo. Não equivale a garantia de decoder independente em todo fabricante.
- [Android WindowInsets](https://developer.android.com/reference/android/view/WindowInsets): barras, recortes e IME usados para dimensionar conteúdo e painéis sem duplicar padding.

Consulta em 01/10/2026. Essas referências explicam contratos e decisões; a reprodução com áudio e o layout ainda precisam ser verificados no A14.

Pesquisa em fontes primárias oficiais e repositórios dos autores, consultados em 30/09/2026 e atualizados em 01/10/2026 para a base 0.6. A base foi escrita para este projeto, sem copiar código GPL das referências. ML Kit Selfie Segmentation, MediaPipe Tasks Vision, MagicTouch v1 e Media3 são incorporados; os demais projetos abaixo são fontes de estudo, salvo indicação explícita.

## Projetos e exemplos

| Projeto | Fonte | Licença verificada / observação | Uso na investigação |
| --- | --- | --- | --- |
| Android camera-samples | [GitHub oficial Android](https://github.com/android/camera-samples) | Exemplos com cabeçalhos Apache 2.0; preservar o cabeçalho do arquivo escolhido se houver reaproveitamento futuro | Referências de Camera2/CameraX, ciclo de vida, captura e controles; nenhum trecho copiado |
| Open Camera | [Site do autor](https://opencamera.org.uk/), [ajuda sobre perfis de vídeo](https://opencamera.org.uk/help.html) e [código oficial SourceForge](https://sourceforge.net/p/opencamera/code/ci/master/tree/) | GPL v3 ou posterior declarado pelo autor | Referência funcional de Camera2, controles e perfis Log/gamma para edição; nenhum código GPL copiado nesta base |
| CameraView | [GitHub do autor](https://github.com/natario1/CameraView) e [LICENSE](https://raw.githubusercontent.com/natario1/CameraView/master/LICENSE) | MIT; arquivo preserva atribuições de Otalia Studios e WonderKiln | Referência de API de câmera, filtros e processamento de frames; não incorporado |
| MotionCam histórico | [Endereço original](https://github.com/mirsadm/motioncam), [cópia histórica preservada](https://github.com/TadiT7/motioncam) e [LICENSE da cópia](https://raw.githubusercontent.com/TadiT7/motioncam/master/LICENSE) | GPL v3 na cópia histórica; endereço original indisponível na consulta | Referência histórica de pipeline RAW e fotografia computacional; não é o código atual do produto comercial nem base indicada para aparelhos simples |
| MediaPipe | [Repositório e LICENSE no tag v0.10.14](https://github.com/google-ai-edge/mediapipe/blob/v0.10.14/LICENSE) e [InteractiveSegmenter desse tag](https://github.com/google-ai-edge/mediapipe/blob/v0.10.14/mediapipe/tasks/java/com/google/mediapipe/tasks/vision/interactivesegmenter/InteractiveSegmenter.java) | Apache 2.0; notices do runtime e modelo são próprios | Incorporado na 0.6: `com.google.mediapipe:tasks-vision:0.10.14`, API por imagem e RegionOfInterest para objetos selecionados |
| MediaPipe samples | [GitHub oficial](https://github.com/google-ai-edge/mediapipe-samples) | Apache 2.0 | Fluxo Android de segmentação e gestão de máscaras |
| Selfie Segmentation model | [Model card oficial](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Selfie%20Segmentation.pdf) | Apache 2.0 declarado no model card | Modelo leve de pessoa/fundo; licença do modelo conferida separadamente do SDK |
| MagicTouch v1 | [Artefato oficial versionado](https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite), [model card oficial](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MagicTouch.pdf) e [download no exemplo em commit fixo](https://github.com/google-ai-edge/mediapipe-samples/blob/30a8280edf51617f0f4b3e8f8fdaa7174eb6b9ef/examples/interactive_segmentation/android/app/download_model.gradle) | Apache 2.0 declarado no model card; licença e atribuição acompanham o asset | Incorporado na 0.6 sem modificar o modelo: recorte local do objeto indicado por ponto; não é um modelo de profundidade ou identidade |
| GPUVideo-android | [GitHub do autor](https://github.com/MasayukiSuda/GPUVideo-android) | MIT | Exemplo de shaders, gravação Camera2, MediaCodec e processamento GPU; não adotado como dependência |
| Grafika | [GitHub Google](https://github.com/google/grafika) | Apache 2.0; arquivado em 15/04/2025 | Referência histórica de EGL, SurfaceTexture, threads e codificação; projeto sem suporte para adoção direta |
| ML Kit Selfie Segmentation | [Guia Android oficial](https://developers.google.com/ml-kit/vision/selfie-segmentation/android) | Exemplos documentados sob Apache 2.0; o SDK binário possui termos próprios | Incorporado na 0.4: `com.google.mlkit:segmentation-selfie:16.0.0-beta6`, modelo embarcado, STREAM_MODE |
| AndroidX Media3 | [Guia oficial ExoPlayer](https://developer.android.com/media/media3/exoplayer/hello-world), [repositório AndroidX](https://github.com/androidx/media) e [constantes do tag 1.4.1](https://raw.githubusercontent.com/androidx/media/1.4.1/constants.gradle) | Apache 2.0 nos fontes AndroidX; preservar notices das dependências | Incorporado na 0.5: `media3-exoplayer` e `media3-ui:1.4.1`; player interno, controles, tratamento de erros e liberação dos decoders |

Antes de incorporar trechos ou bibliotecas, fixar versão/commit, registrar origem e licenças/notices, preservar atribuição e verificar compatibilidade técnica. A licença do aplicativo não é determinada só por esta tabela. Serviços e SDKs podem ter termos distintos dos exemplos da documentação.

## APIs que fundamentam a base

| Fonte primária | Decisão sustentada |
| --- | --- |
| [CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics) | Descobrir capacidades, hardware level, intervalos de sensor, foco, tamanhos, modos AE/AWB e tonemapping em tempo de execução |
| [CaptureRequest](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest) | Aplicar controles automáticos/manuais por request e compreender a relação entre AE, exposição, ISO, foco e cor |
| [CONTROL_AF_REGIONS](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AF_REGIONS) e [CONTROL_AF_TRIGGER](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AF_TRIGGER) | Foco ao toque da 0.4: considerar crop/proporção/distorção e enviar START/CANCEL somente em capturas únicas, com IDLE no repetidor |
| [CONTROL_AF_STATE](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#CONTROL_AF_STATE) | Feedback da lente na 0.6: separar solicitação de foco, FOCUSED_LOCKED e NOT_FOCUSED_LOCKED; nenhuma máscara digital confirma autofocus |
| [TONEMAP_MODE](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#TONEMAP_MODE) | Investigação inicial de curvas no sensor; a versão 0.2 usa Flat local para dispensar essa capacidade opcional |
| [EGL14](https://developer.android.com/reference/android/opengl/EGL14) | Contexto e superfícies GLES compartilhadas para prévia e entrada do recorder |
| [SurfaceTexture](https://developer.android.com/reference/android/graphics/SurfaceTexture) | Receber frames em textura externa, respeitar transformações e consumir no thread que possui o contexto GL |
| [Camera preview / orientação Camera2](https://developer.android.com/media/camera/camera2/camera-preview) | Calcular orientação relativa do sensor/tela e preservar proporção na prévia; a base 0.5 usa o mesmo contrato de geometria na GPU e no toque |
| [SurfaceTexture.getTransformMatrix](https://developer.android.com/reference/android/graphics/SurfaceTexture#getTransformMatrix(float[])) | Ler a matriz do quadro efetivamente atualizado, preservando crop e transformação de coordenadas antes de orientar a saída |
| [MediaRecorder.getSurface](https://developer.android.com/reference/android/media/MediaRecorder#getSurface()) | Gravar os frames processados pela GPU em uma Surface de entrada preparada para H.264; finalizar e liberar o recorder conforme seu ciclo de vida |
| [MediaRecorder.setOrientationHint](https://developer.android.com/reference/android/media/MediaRecorder#setOrientationHint(int)) | Distinguir giro de pixels e orientação no contêiner; o hint não gira o quadro de origem e alguns players podem ignorá-lo |
| [StreamConfigurationMap](https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap) | Consultar tamanhos e duração mínima de frame; um tamanho de foto não determina tamanho de vídeo suportado |
| [MediaCodecInfo.VideoCapabilities](https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities) | Verificar combinação de tamanho e cadência do encoder antes de oferecer modo de gravação |
| [CameraEffect](https://developer.android.com/reference/androidx/camera/core/CameraEffect) | Alternativa futura CameraX com efeitos destinados a PREVIEW e VIDEO_CAPTURE |
| [SurfaceProcessor](https://developer.android.com/reference/androidx/camera/core/SurfaceProcessor) | Interface de processamento de superfícies na GPU; opção para reavaliar a engine no futuro |
| [Media3: app básico e ciclo de vida](https://developer.android.com/media/implement/playback-app) e [PlayerView no tag 1.4.1](https://raw.githubusercontent.com/androidx/media/1.4.1/libraries/ui/src/main/java/androidx/media3/ui/PlayerView.java) | Player da 0.5: preparar em onStart/liberar em onStop em API24+, separar pausa do usuário da pausa de ciclo de vida, controles integrados e resize FIT |

Suporte declarado pela API é uma etapa de filtragem, não um benchmark ou garantia da combinação final. Câmera, sessão, encoder, áudio e armazenamento precisam funcionar em conjunto no aparelho físico.

Na base 0.1 investigamos a curva de captura `y = 0.06 + 0.88 * x^(1/2.2)` em oito pontos via TonemapCurve, que dependia de suporte do sensor. A 0.2 substituiu esse caminho por Flat local: `c = 0.12 + 0.76 * c`. A 0.4 acrescentou LumaLog v1 independente. A 0.5 preserva v1 e acrescenta v2, com a mesma curva e compressão reversível de crominância de 40% a 100% de força. LUT e assistência invertem ambas as etapas; os perfis ficam em Imagem com os demais ajustes. Nenhuma das curvas acrescenta faixa dinâmica ao SDR entregue. O processamento visual usa GLES2/EGL; ML Kit produz a máscara de pessoas e MagicTouch produz o recorte de objeto na 0.6.

## Transformação de câmera e orientação na 0.5

O [fonte AndroidX CameraX SurfaceOutputImpl em revisão fixa](https://android.googlesource.com/platform/frameworks/support/+/187e9a2088c3f281cfb617b9bedd94b9a3546d8b/camera/camera-core/src/main/java/androidx/camera/core/processing/SurfaceOutputImpl.java) e o [arquivo atual no repositório AndroidX](https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/processing/SurfaceOutputImpl.java) separam a matriz de SurfaceTexture e a transformação adicional desejada, considerando giro do sensor, espelho e crop. É uma referência primária para evitar aplicar a orientação da câmera duas vezes; CameraX não foi incorporado como dependência nesta base.

O [Camera2Session do WebRTC](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/src/java/org/webrtc/Camera2Session.java) corrige espelho/orientação aplicados pelo sistema ao buffer de câmera. O [helper CameraSession](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/src/java/org/webrtc/CameraSession.java) mostra a composição da matriz modificada. Esses fontes são referências de geometria, sem runtime WebRTC incorporado ou trechos copiados para esta base.

Luma Camera usa uma implementação própria em `FrameGeometry`: identifica e normaliza a orientação ortogonal observada na matriz efetiva de cada quadro, preserva os limites de crop e a conversão vertical GLES e depois aplica orientação final. Transformações não ortogonais mantêm a matriz original. A TextureView usa matriz identidade, a prévia é FIT e a frontal é espelhada somente na tela. Essa decisão não constitui garantia de que todos os produtores/câmeras anunciam as mesmas matrizes; exige confirmação em Android real.

Quando um candidato AVC oferece dimensões orientadas, a GPU gira os pixels e o recorder recebe hint zero. O fallback usa pixels/dimensões originais e um único hint relativo. A documentação de [VideoCapabilities](https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities) sustenta a triagem de tamanho/cadência; MediaRecorder escolhe o codec efetivo, portanto essa consulta não comprova sessão ou desempenho no A14.

## Referências de experiência: Retrato, Cinema e Log

O [suporte oficial Samsung sobre Portrait/Live Focus](https://www.samsung.com/us/support/answer/ANS10003225/) descreve desfoque ao redor do assunto e ajustes de efeitos para foto e vídeo nos aparelhos compatíveis. Essa experiência orienta o Retrato IA da base: pessoa preservada, intensidade ajustável e prévia do efeito. Não há acesso nem portabilidade presumida do algoritmo proprietário Samsung, e a página não comprova Portrait Video nativo no A14.

O [guia oficial Apple de Cinematic](https://support.apple.com/guide/iphone/record-video-in-cinematic-mode-ipha0706e2bc/ios) descreve assunto em foco, transições automáticas, seleção por toque e alteração posterior nos modelos compatíveis. A base 0.6 aproxima a experiência de transição entre assunto e fundo usando uma máscara de pessoas ou um objeto selecionado por ponto; o controle dinâmico complementa a chave única de desfoque IA. Não reproduz o mapa de profundidade, tracking de identidade ou edição posterior do efeito do iPhone; o MP4 recebe o resultado já composto.

A [ajuda oficial Open Camera](https://opencamera.org.uk/help.html) documenta perfis Log voltados à edição e gamma configurável, com suporte condicionado à API/câmera; o [histórico do autor](https://opencamera.org.uk/history.html) registra perfis gamma e JTLog/JTLog2. Essa referência sustenta a viabilidade de um perfil simulado, coerente com o relato do usuário de ter gravado Log em outro app no mesmo celular. LumaLog usa uma fórmula própria descrita em [LUMALOG.md](../guides/LUMALOG.md), sem copiar código ou perfis GPL. O v2 comprime crominância de modo reversível; assistência de exibição e LUT 3D da versão/força selecionadas são componentes próprios da base, preservando a LUT v1 para clipes anteriores.

## Segmentação e desfoque local

O [guia MediaPipe Image Segmenter Android](https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter/android) mostra modelo armazenado em assets, funcionamento por imagem/vídeo/live stream, listener assíncrono e entrega de máscaras. No modo LIVE_STREAM, entradas novas são ignoradas se a tarefa estiver ocupada. É uma referência de gestão de frames; o wrapper de objetos da 0.6 usa a tarefa InteractiveSegmenter por imagem, com agendamento próprio.

O [guia e benchmark oficial](https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter) lista versões SelfieSegmenter com entrada 256×256 e 144×256. As latências apresentadas para a pipeline no Pixel 6 ficam aproximadamente em 33–35 ms nos modelos leves. São resultados do fornecedor nesse aparelho: não equivalem a teste do Luma Camera nem garantem GPU mais rápida que CPU em outra configuração.

O [model card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Selfie%20Segmentation.pdf) descreve segmentação de pessoas próximas e limitações em detalhes finos, baixa iluminação, ruído, movimento rápido, oclusões e pessoas distantes ou em escalas muito diferentes. O desfoque por máscara não é um mapa de profundidade completo e pode incluir mais de uma pessoa.

Como alternativa, o [ML Kit Selfie Segmentation](https://developers.google.com/ml-kit/vision/selfie-segmentation/android) fornece modelo embarcado, STREAM_MODE com informação de frames anteriores e máscara bruta de resolução reduzida. A documentação informa acréscimo de aproximadamente 4,5 MB, API mínima 23 e latência de 25–65 ms no Pixel 4. A API permanece beta, sem SLA ou política de depreciação; isso deve entrar na avaliação de manutenção.

Na 0.4 foi incorporado ML Kit Selfie Segmentation `16.0.0-beta6`. O [guia oficial](https://developers.google.com/ml-kit/vision/selfie-segmentation/android) confirma modelo vinculado ao app na compilação, STREAM_MODE, máscara de confiança e limitação de chamadas para câmera ao vivo. A base analisa lado máximo de 256/512 px, limita submissão a 5/8 Hz, mantém uma tarefa em voo, suaviza contornos e expira máscaras atrasadas. Essas escolhas próprias não são benchmarks do SDK nem garantia de precisão no A14.

### Objetos: versão e contrato fixados na 0.6

O [guia Interactive Segmenter Android](https://developers.google.com/edge/mediapipe/solutions/vision/interactive_segmenter/android) e o [overview atual](https://developers.google.com/edge/mediapipe/solutions/vision/interactive_segmenter) mostram seleção de uma região e sua máscara. Em 17/08/2026, esses guias foram atualizados para uma API de imagem preparada e strokes; o overview também passou a apresentar outro artefato recomendado. A base fixa Tasks Vision `0.10.14` e MagicTouch float32 versão `1`, sem usar `latest.release` ou o modelo latest.

O [fonte oficial v0.10.14](https://github.com/google-ai-edge/mediapipe/blob/v0.10.14/mediapipe/tasks/java/com/google/mediapipe/tasks/vision/interactivesegmenter/InteractiveSegmenter.java) confirma a API `segment(MPImage, RegionOfInterest)` por imagem e masks no resultado. O app repete essa operação em executor CPU serial, com uma tarefa em voo e até três submissões por segundo; não usa a nova API setImage/strokes. O ponto de seleção é convertido para a imagem orientada. Políticas próprias validam o componente, auxiliam um ponto interior para movimento lento e expiram máscaras; não implementam identificação ou profundidade.

| Identidade do modelo incorporado | Valor |
| --- | --- |
| Origem | [MagicTouch float32 v1](https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite) |
| Asset local | `app/src/main/assets/models/magic_touch_v1.tflite`, sem modificação |
| Tamanho | 6.227.884 bytes |
| SHA-256 | `E24338A717C1B7AD8D159666677EF400BABB7F33B8AD60C4D96DB4ECF694CD25` |
| Entrada inspecionada | float32 `[1,512,512,4]` |
| Saída inspecionada | float32 `[1,512,512,1]`, SIGMOID de foreground |
| Licença/atribuição | `MAGIC_TOUCH_LICENSE.txt` e `MAGIC_TOUCH_NOTICE.txt`, junto ao modelo |

O [model card histórico MagicTouch](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MagicTouch.pdf) declara Apache 2.0 e limitações para alvos pequenos, objetos próximos, detalhes finos, pouca luz, oclusões e movimento. Ele descreve saída de dois canais com softmax; o binário v1 distribuído e inspecionado tem uma saída sigmoid de um canal. O wrapper aceita essa saída real, sem aplicar a hipótese de dois canais do card. O desempenho GPU citado para Pixel 7 pertence ao fornecedor; a pipeline CPU desta base e o A14 não foram medidos. Atualização de máscaras e identidade do alvo precisam de validação física.

Inferência com modelos embarcados dispensa servidor e download de modelo ao usar o app. Dependências e assets são obtidos pelo computador na montagem. INTERNET é removida do manifesto mesclado; a captura e a segmentação seguem locais. Processamento, bateria, aquecimento, tamanho do APK e qualidade de contornos ainda exigem medição no aparelho. O runtime nativo MediaPipe acrescenta bibliotecas por ABI e o modelo acrescenta aproximadamente 6,2 MB; tamanho final depende do APK distribuído.

## Referência de aparelho acessível

A [ficha técnica oficial Samsung do Galaxy A14 5G, variante SM-A146P](https://www.samsung.com/pt/smartphones/galaxy-a/galaxy-a14-5g-green-128gb-sm-a146plggeub/) informa sensor principal de 50 MP e gravação FHD 1920×1080 a 30 fps. Esses valores descrevem a variante publicada, não capacidades Camera2 medidas neste projeto. Não extrapolar para todas as variantes ou câmeras sem relatório físico.

1080p corresponde a cerca de 2,07 MP por frame. A contagem de megapixels de foto não permite prometer vídeo de 50 MP, 4K, 60 fps ou desbloqueio de processamento proprietário. O aplicativo busca controle e uso eficiente das capacidades acessíveis; capacidade real e qualidade precisam ser comprovadas no celular.

## Pesquisa da 0.8 — movimento e reprodução

- [OpenCV: estimativa de movimento global](https://docs.opencv.org/4.5.0/d4/d2c/group__videostab__motion.html): referência para estimação robusta e limite de inclusão/recorte. Implementação própria em Kotlin com blocos/consenso, sem dependência OpenCV; apenas translação causal, sem reivindicar estabilização neural geral.
- [Android/Media3: tipos de superfície](https://developer.android.com/media/media3/ui/surface): SurfaceView favorece consumo e temporização em reprodução comum. A 0.8 conserva a superfície eficiente e remove sobreposição/recorte decorativo do viewport.
- [ML Kit: segmentação de pessoas no Android](https://developers.google.com/ml-kit/vision/selfie-segmentation/android): STREAM_MODE já usa informação temporal; entrada recomendada ao menos 256×256 e descarte de frames enquanto ocupado. A base mantém os perfis anteriores: Leve pode ficar abaixo da dimensão recomendada no lado curto; Qualidade tem mais informação e maior custo. O filtro adicional não recupera detalhes ausentes.

Consulta em 01/10/2026. As fontes orientam decisões; não são benchmarks do código próprio ou do A14.


## Pesquisa da 0.9 — monitor e controles

- [Camera2: CONTROL_AE_LOCK](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AE_LOCK) e [CONTROL_AWB_LOCK](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AWB_LOCK): travas dos controladores automáticos. A base verifica características e chaves disponíveis, sem anunciar uma trava física ausente.
- [MediaRecorder.getMaxAmplitude](https://developer.android.com/reference/android/media/MediaRecorder#getMaxAmplitude()): pico desde a chamada anterior; a primeira chamada retorna zero. Usado como medidor relativo, sem segundo AudioRecord e sem reivindicar dBFS calibrado.
- [MediaCodecInfo.VideoCapabilities.getBitrateRange](https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities#getBitrateRange()): faixa de bitrate consultada para limitar o alvo na geometria final. O MediaRecorder continua escolhendo o codec efetivo.
- [SensorManager.getRotationMatrixFromVector](https://developer.android.com/reference/android/hardware/SensorManager#getRotationMatrixFromVector(float%5B%5D,%20float%5B%5D)) e [coordenadas dos sensores](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview#sensors-coords): nível obtido pela projeção de gravidade, com adaptação à rotação da tela; não pelo roll Euler em torno de um eixo diferente.

Consulta em 01/10/2026. Zebra, false color e contornos são implementações próprias de auxílio visual no domínio SDR. Histogramas e contratos sintéticos não certificam calibração fotométrica ou desempenho no aparelho.

## Ferramentas e navegação — 0.12

Referências oficiais consultadas em 01/10/2026: [Blackmagic Camera](https://www.blackmagicdesign.com/products/blackmagiccamera/) e [especificações](https://www.blackmagicdesign.com/products/blackmagiccamera/techspecs), [RED Rack Focus](https://docs.red.com/955-0127_v7.4/Raven-Operation-Guide/en-us/Content/5_Advanced_Menus/6_Focus/Rack_Focus.htm), [MediaRecorder](https://developer.android.com/reference/android/media/MediaRecorder), [AudioRouting](https://developer.android.com/reference/android/media/AudioRouting) e [camera-samples oficial](https://github.com/android/camera-samples). Orientam controles, monitores, LUTs, pontos A/B e contratos de áudio/próximo arquivo. Nenhuma garantia de hardware foi inferida da referência.

## Estabilização — 0.13

[OpenCV — Global Motion Estimation](https://docs.opencv.org/4.13.0/d4/d2c/group__videostab__motion.html), consultado em 01/10/2026: modelos de movimento rígido, consenso robusto e restrições de inclusão no recorte. Referência conceitual para algoritmo Kotlin próprio; OpenCV não foi adicionado como dependência.

[Android Camera2 — CONTROL_VIDEO_STABILIZATION_MODE](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE): suporte e combinações de estabilização do dispositivo são condicionais. A correção local não anuncia EIS/OIS ausente nem depende dessa disponibilidade.
