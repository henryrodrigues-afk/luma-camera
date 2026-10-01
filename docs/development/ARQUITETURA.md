# Arquitetura do Luma Camera 0.13

Base Android `0.13.0-stabilization`, código de versão 13. Atualização: **01/10/2026**. A base combina processamento local na GPU, segmentação de pessoas e objetos selecionados, controles individuais, foco ao toque e geometria compartilhada. A 0.10 corrige o relógio dos frames gravados e finalização do MP4, adapta controles à janela/fonte e acrescenta recuperação interna de reprodução com Media3 e MediaPlayer. Compilação e testes integrados estão descritos em [validação](../validation/VALIDACAO.md); não há evidência de execução desta versão em Android real. O [guia de foco e desfoque](../guides/FOCO_E_DESFOQUE.md) descreve a operação pela interface.

## Relógio, duração e janela — 0.10

`RecordingTimeline` usa `System.nanoTime()` para a apresentação de frames ao encoder. O timestamp da câmera serve somente para eliminar frames duplicados, pois a origem do relógio da câmera pode variar. Frames atrasados preservam o intervalo real; FPS alvo limita submissões sem acelerar o vídeo. O contador usa a mesma origem monotônica do gravador.

Ao parar, `MediaRecorder.stop()` finaliza o MP4 antes de desligar/liberar a superfície EGL do encoder. `RecordingValidation` verifica duração, primeira amostra e duração real esperada antes de publicar; arquivo válido é preservado para recuperação se a publicação falhar. VideoStore consulta metadados em trabalho de biblioteca, com cache limitado, quando a duração MediaStore está ausente. Não escreve nas colunas de metadados somente leitura. `MediaTime` trata duração indisponível separadamente da posição zero. Veja [DURACAO_E_GRAVACAO.md](DURACAO_E_GRAVACAO.md).

`CameraWindowInsets` aplica barras/recortes/IME uma vez ao conteúdo e informa a área útil da janela. `ResponsiveUiPolicy` decide dock, painel e disposição de rótulos usando essa área e fontScale. Controles adaptam altura ao texto, painéis mantêm rolagem e indicadores usam o espaço medido entre HUDs. Animações de interface não alteram os frames enviados ao encoder.

## Objetivo e ferramentas

Oferecer liberdade criativa por recursos físicos anunciados pela câmera e efeitos digitais que independem de controle manual do sensor. Retrato, Cinema e LumaLog atuam sobre o SDR já processado pelo aparelho. A experiência de Samsung Retrato e Apple Cinematic orienta os recursos; a implementação não usa código proprietário desses fabricantes.

Kotlin, interface Android nativa, Camera2, GLES2/EGL e MediaRecorder. minSdk 29, compileSdk/targetSdk 34, AGP 8.2.2, Kotlin 1.9.22, Gradle 8.5 e JDK 17. Dependências incorporadas: `com.google.mlkit:segmentation-selfie:16.0.0-beta6`, `com.google.mediapipe:tasks-vision:0.10.14` e `androidx.media3:media3-exoplayer`/`media3-ui:1.4.1`. Os modelos são embarcados. Tasks Vision admite API 24; o app mantém API 29. O tag Media3 1.4.1 usa compileSdk 34; a escolha evita elevar o SDK do protótipo durante esta correção. Distribuição exige revisar SDK, dependências, notices e requisitos de loja.

## Pipeline implementada

```text
Interface: chaves individuais; toque; parâmetros e preferências
                         |
              CameraEngine / Camera2
                         |
      Uma saída de câmera: SurfaceTexture externa OES
                         |
               GpuPipeline / EGL / GLES2
           normalização da matriz observada da câmera
                         |
                rawFBO: imagem limpa SDR
                   |        |           |
          blur Gaussiano     |           +--> medição 16×16, até 4 Hz
       2 passes <=480 px     |                         |
                   |        |               ExposureAutomation em modo misto
                   |        +--> análise local                  |
                   |             Pessoas: ML Kit <=256/512 px   +--> Camera2
                   |                      <=5/8 submissões/s
                   |             Objetos: MagicTouch <=256 px
                   |                      <=3 submissões/s + ponto selecionado
                   |             máscara assunto/fundo
                   |             alinhamento + suavização + validade
                   |                         |
                   +---------> effectsFBO ping-pong
                          cor, desfoque, Flat, LumaLog, rastro
                                      |
                      mesmo quadro final destinado ao MP4
                            |                     |
                  EGL/TextureView       EGL/Surface do MediaRecorder
                  giro + espelho frontal   giro físico ou hint único
                  inversa Log opcional             |
                  somente na exibição     MP4 H.264 + AAC opcional
                                                   |
                                  arquivo privado → MediaStore/galeria
```

`camera.frag` amostra a textura OES com a matriz observada e normalizada e escreve em uma textura 2D sem efeitos locais. `rawFBO` é um nome interno para essa imagem limpa; não representa sinal RAW do sensor. O desfoque Gaussiano em duas passagens separáveis trabalha em textura reduzida. O histórico alterna buffers para impedir leitura e escrita da mesma textura e compor rastro temporal.

O compositor entrega o quadro final à prévia e ao MediaRecorder. Assistência Log é a única correção de exibição própria: usa a inversa somente ao desenhar a prévia, preservando a curva gravada. MediaRecorder produz H.264/AAC e o contêiner MP4; não há MediaCodec ou MediaMuxer próprios. Equivalência visual, orientação, timestamps, áudio e qualidade do arquivo exigem Android real.

## Geometria de câmera, prévia, toque e encoder

`FrameGeometry` centraliza orientação relativa do sensor/tela, dimensões orientadas, viewport FIT e conversão inversa dos toques. A interface redimensiona a TextureView para caber no espaço disponível, preserva a proporção e usa `Matrix()` identidade. A GPU faz a rotação de exibição uma única vez e espelha a frontal somente na prévia. Barras são excluídas dos alvos ao toque.

O compositor lê `SurfaceTexture.getTransformMatrix` após cada `updateTexImage`. Essa matriz pode já conter orientação/espelho do produtor além de crop e inversão vertical. `canonicalCameraTransform` identifica transformações ortogonais de eixos, remove o giro/espelho observado e reconstrói somente os limites do crop com a conversão vertical nativa do GLES. Uma matriz simples com Y invertido permanece equivalente. Se houver transformação não ortogonal, perspectiva ou valores inválidos, preserva a matriz original; não inventa uma correção a partir de hipóteses do sensor.

Quando um candidato AVC oferece as dimensões orientadas, o recorder recebe essas dimensões e a GPU gira os pixels fisicamente; `setOrientationHint(0)` evita orientação duplicada. Se somente as dimensões originais forem elegíveis, o encoder recebe uma cópia sem giro e usa o hint da orientação relativa no contêiner. A frontal gravada não é espelhada. A [API do hint](https://developer.android.com/reference/android/media/MediaRecorder#setOrientationHint(int)) acrescenta orientação ao arquivo, sem girar os pixels, e pode ser interpretada de modo diferente por players.

A consulta a MediaCodecList/VideoCapabilities é uma triagem de candidatos. MediaRecorder escolhe a implementação efetiva; a consulta não seleciona esse codec nem prova que ele prepara ou sustenta a combinação. Os dois caminhos de orientação e a reprodução de vídeos anteriores exigem conferência no A14. A base não regrava arquivos antigos para corrigir orientação.

## Segmentação local e validade das máscaras

`PortraitSegmenter` usa ML Kit Selfie Segmentation em STREAM_MODE. A biblioteca e o modelo são incorporados na compilação. O app cria Bitmap com origem superior esquerda, corrige rotação e envia a imagem localmente. O resultado é alinhado à textura original GLES com origem inferior esquerda por `PortraitMaskPolicy`.

A análise recebe lado máximo de 256 px no perfil Leve e 512 px no perfil Qualidade, com intervalos mínimos de 200/125 ms: até 5/8 análises por segundo. Há uma única tarefa em voo, sem fila acumulada; não se faz readback quando o detector está ocupado. As taxas são limites de submissão, não desempenho medido nem garantia de inferência. A GPU pode renderizar e gravar entre máscaras, usando a mais recente.

STREAM_MODE aproveita quadros anteriores. TemporalMaskPolicy acrescenta filtro adaptativo por tempo: pequenas oscilações recebem EMA, mudanças fortes atualizam rapidamente; geração/geometria/intervalo/corte de cena reiniciam a história. Pessoas usam histerese de presença; objetos filtram somente componentes atuais já válidos e preservam a perda travada. Estabilidade e suavidade de borda são controles independentes. A força permanece integral até 600 ms de idade, diminui progressivamente e chega a zero em 1.200 ms. Desativar o recurso invalida resultados pendentes. Falhas de inferência têm retentativa limitada e mensagem própria; não devem derrubar a gravação limpa. A máscara de pessoas distingue pessoas e fundo; detalhes finos, baixa luz e movimento exigem avaliação física.

No modo Objetos, `ObjectSegmenter` incorpora **MagicTouch v1** por Tasks Vision `0.10.14`, usando a API síncrona `segment(MPImage, RegionOfInterest)` dessa versão. Cada análise é de uma imagem, executada em executor serial na CPU. Há uma tarefa em voo, sem fila de frames antigos, e intervalo mínimo de 334 ms: até três submissões por segundo. O readback recebe lado máximo de 256 px; o SDK prepara a entrada do modelo. O cliente nativo permanece exclusivamente no executor de inferência e é fechado na mesma fila, evitando corrida com `segment`.

O asset tem 6.227.884 bytes, entrada float32 `[1,512,512,4]` e saída float32 `[1,512,512,1]` com SIGMOID. RGB e indicação do ponto selecionado compõem a entrada; a saída distribuída é uma única confiança de foreground. Esse contrato foi inspecionado no binário fixado: não se supõe a saída de dois canais com softmax descrita no model card histórico. Licença Apache 2.0 e notices acompanham o artefato; origem e hash estão em [REFERENCIAS.md](REFERENCIAS.md).

`ObjectMaskPolicy` converte o ponto GLES para a imagem orientada, conserva o componente associado ao ponto e auxilia a próxima indicação por um ponto interior da máscara anterior. Essa assistência favorece movimento lento; não reconhece identidade, não faz tracking óptico completo e não estima profundidade. O limite de movimento compara os centroides das máscaras consecutivas, e a perda de recorte trava novas análises até uma nova seleção, sem reacquisição automática. A máscara mantém força até 1.500 ms, diminui até zero em 4.000 ms e passa pelo alinhamento `PortraitMaskPolicy` para a origem GLES. Submissões, callbacks e seleção usam gerações/revisões; mudar modo/ponto descarta resultados antigos. Máscara vazia, inválida ou expirada não aplica desfoque IA ao quadro inteiro.

Pessoas mantém seu modelo leve e perfis próprios; o modelo interativo só é usado em Objetos após seleção explícita. O runtime nativo MediaPipe e o modelo aumentam o APK. Ainda não há benchmark de CPU, latência de máscara ou precisão em Android/A14 para a 0.8.

## Pessoas, Objetos, Cinema e foco da lente

Na interface, **Desfoque por IA** é a chave principal para a segmentação compartilhada (`portraitEnabled || cinematicEnabled`). **Transição dinâmica de foco** acrescenta Cinema à mesma máscara; desligar a chave principal desliga também Cinema. Intensidade `portraitStrength`, de 0 a 1, é um controle único; perfis Leve/Qualidade pertencem à análise de Pessoas. Os campos internos preservam compatibilidade com as preferências anteriores, sem expor dois modos de desfoque duplicados. O desfoque oval permanece com parâmetros próprios; quando combinado, o compositor considera também sua contribuição.

`subjectMode` escolhe Pessoas (`0`, padrão) ou Objetos (`1`). `SubjectFocusPolicy` normaliza o modo, exige `objectPointSelected` e coordenadas finitas dentro de 0–1 para o modo Objetos e condiciona a aplicação à máscara válida. O centro padrão não constitui seleção. Modo/coordenadas/revisão são preferências; a flag de seleção não é restaurada do armazenamento. Reabrir ou trocar câmera invalida o objeto selecionado.

O desfoque preserva o assunto recortado ou o fundo, conforme o alvo. Cinema interpola esse alvo durante o intervalo selecionado de 0,2–3 s. O automático prioriza a máscara válida; sem assunto, o efeito IA não é aplicado. Em Pessoas, um toque consulta confiança: >=0,6 escolhe pessoas, <=0,4 escolhe fundo, mantendo o estado intermediário para evitar oscilações. A máscara pode incluir várias pessoas, sem escolha de indivíduos separados.

Em Objetos, o primeiro toque seleciona o ponto para o recorte. Com Cinema desligado, novo toque refaz a seleção. Com Cinema ativo e objeto já selecionado, os próximos toques consultam a máscara existente para escolher assunto/fundo, preservando o ponto do recorte. **Selecionar outro objeto** limpa a seleção e prepara o próximo toque para um novo recorte. Esse contrato também orienta a ação ao perder o objeto: um toque de Cinema não se transforma silenciosamente em nova seleção.

O HUD usa **Lente · …** para a operação física e **Desfoque IA · …** para máscara, espera de seleção, análise e perda. “Pessoa/objeto/fundo preservado” descreve o compositor; “foco confirmado” depende do resultado Camera2, sem inferir sucesso da lente a partir da IA.

`FrameGeometry.sourcePoint` converte o toque na prévia usando a mesma orientação, espelhamento e viewport FIT usados na cópia GPU. Depois aplica StabilizationTransform.sourcePoint ao snapshot do sampler para recuperar o ponto realmente exibido. Para a máscara, usa coordenadas GLES; para `CameraEngine.focusAt`, converte Y à origem superior esquerda do vídeo sem rotação. `FocusMeteringPolicy` transforma esse ponto em um pequeno retângulo dentro do crop visível: considera o crop retornado, offsets e o recorte adicional por diferença entre sensor e proporção do vídeo. O engine escolhe activeArray ou preCorrectionActiveArray conforme o modo de distorção retornado.

O foco físico ao toque exige AF_AUTO e os controles de AF. Regiões AF/AE são enviadas apenas quando anunciadas; a região AE só participa com exposição automática. CANCEL e START são capturas únicas e o repetidor volta a IDLE. O alvo permanece até limpar, trocar câmera ou alternar foco manual; ajustes de exposição não repetem START. Sem regiões AF, solicita refoco geral e informa que a área física não pode ser escolhida. Sem AF_AUTO ou com foco manual ligado, preserva o estado da lente e dá feedback separado da gravação.

`clearFocusTarget` retorna ao autofocus normal, preferindo CONTINUOUS_VIDEO. O foco físico ao toque aceita qualquer assunto e funciona com desfoque IA desligado; não depende do modo Pessoas/Objetos. O aviso inicial indica tentativa. AF_STATE_FOCUSED_LOCKED confirma o autofocus; NOT_FOCUSED_LOCKED informa que ele não foi confirmado. A transição visual do desfoque não impõe a velocidade óptica da lente; a segmentação não controla continuamente a lente por tracking de profundidade.

## Cor e LumaLog

Ganho, temperatura, contraste, saturação, nitidez, Flat e rastro continuam independentes. Controles desligados recebem valores neutros; edições somente locais não reemitem requests Camera2. Flat aplica `c = 0.12 + 0.76 * c`.

LumaLog aproxima a transferência sRGB para luz linear e aplica `0.09375 + 0.84375 * ln(1 + 63 * linear) / ln(64)`, com mistura pela força selecionada. O v1 legado conserva essa curva. O v2 acrescenta compressão reversível de crominância: calcula luminância ponderada `Y` e aplica `Y + (RGB - Y) * (1 - 0.4 * força)`. A 100%, retém 60% da crominância, produzindo uma imagem flat e dessaturada. Não depende de TONEMAP_MODE nem de Log nativo. A câmera e o ISP já podem ter alterado o sinal SDR antes desse ponto; a transformação não recupera altas luzes cortadas nem identifica o espaço de cor do sensor.

A assistência desfaz compressão de crominância e curva apenas na prévia. `SimulatedLog.decodeCube` produz LUT red-fastest da versão/força selecionada; no v2 a inversa é realmente tridimensional porque os canais dependem de `Y`. Um cache escalar de 16.385 amostras acelera a exportação. Os valores estendidos nas bordas da LUT permanecem em ponto flutuante, sem recorte antecipado: o editor deve limitar a saída após interpolar. A interface exporta pelo seletor Android em worker separado, com grade de 33 pontos a 0/100% e 65 nas forças intermediárias. Assets v1 e v2 a 100% são separados; a LUT legado foi preservada.

**Imagem → Preparar imagem para edição** é uma ação explícita aplicada uma vez: ativa v2 a 100%, desliga assistência, ganho, temperatura, contraste, saturação, nitidez, Flat e rastro. Preserva ajustes físicos e desfoques. Não cria bloqueio permanente dos efeitos; controles reativados depois continuam aplicados. A LUT inversa desfaz somente Log, conservando quaisquer ajustes anteriores; rastro é composto no domínio gravado. Versão/força ficam estáveis durante cada clipe, e o nome do vídeo identifica o perfil. Fórmula, interpretação e uso estão em [LumaLog](../guides/LUMALOG.md).

## ISO e obturador separados

| ISO manual | Obturador manual | Exposição |
| --- | --- | --- |
| Desligado | Desligado | AE nativo controla ambos |
| Ligado | Desligado | AE desligado; ISO fixo; automação ajusta exposição |
| Desligado | Ligado | AE desligado; exposição fixa; automação ajusta ISO |
| Ligado | Ligado | AE desligado; ambos fixos nos limites |

Modo misto exige MANUAL_SENSOR e limites válidos. Medição limpa 16×16 ocorre no máximo a cada 250 ms, evitando que os efeitos alimentem a automação. `ExposureAutomation.next` corrige somente o parâmetro automático, com fator `(target/luminance)^0.35` limitado a 0,7–1,4 por passo, respeitando sensor e duração do quadro. Amostras não finitas mantêm o estado. EV controla a compensação nativa em AE, altera o alvo local em modo misto e não muda os dois parâmetros fixados. WB físico e temperatura local são separados.

## Interface, biblioteca, ciclo de vida e privacidade

MainActivity apresenta sete menus: **Gravação**, **Imagem**, **Câmera**, **Foco e desfoque**, **Estabilização**, **Monitor Pro** e **App**. Imagem reúne Log, Flat e ajustes criativos em uma expansão de cor/efeitos. Foco e desfoque reúne distância física, automático da lente, escolha de assunto Pessoas/Objetos, desfoque IA e transição dinâmica. SharedPreferences guarda chaves, parâmetros e preferências; a seleção de objeto é transitória. Trocar câmera mantém efeitos e retorna sensor ao automático. LumaLog e desfoque IA não dependem de MANUAL_SENSOR; CPU, GPU, encoder e orçamento de energia ainda precisam sustentar a composição.

A 0.7 usa ícones vetoriais com paths cacheados, ripple nativo e cartões. Sliders mantêm atualização imediata no engine, mas persistência espera 250 ms e é concluída no fim do toque/fechamento/pausa. Durante arraste, apenas rótulo/HUD necessários são atualizados; estados e textos iguais não causam trabalho extra. O painel tem entrada de 160 ms fora de gravação, respeitando animações desativadas. Em paisagem, o painel é lateral. Os desenhos de interface não entram no framebuffer de efeitos nem no MP4; [DESIGN.md](DESIGN.md) registra os critérios.

LibraryActivity usa VideoPlayerView com Media3 ExoPlayer 1.4.1 e proporção FIT. Controles nativos persistentes ficam fora da superfície: play/pausa/replay, timeline normalizada, ±5s e mudo. Lifecycle guarda posição/intenção e libera decoder em onStop. Arraste pausa o decoder e faz seek no fim; teclado/acessibilidade também podem avançar. Audiofocus/noisy distinguem interrupção do usuário, trilha ausente e mudo. Watchdog considera superfície, buffering, seek, posição e frames, respeitando pausas/interrupções. Recuperação limitada avança entre SurfaceView/Media3, TextureView/Media3 síncrono e TextureView/MediaPlayer Android. O caminho nativo usa prepareAsync, callbacks por identidade/geração, ownership explícito da superfície e posição saudável em caso de erro. Analytics mantém eventos/counters reais para exportação JSON; não exporta bytes do vídeo nem inventa benchmark. Veja [REPRODUCAO.md](../guides/REPRODUCAO.md).

Listagem segue em executor com consulta restrita aos vídeos próprios. Miniaturas têm executor separado, cache de 6 MiB, limite de dois trabalhos pendentes e seleção por janela visível com margem de 180 dp. ImageViews fora dela liberam referências; lista/scroll são preservados quando o álbum não muda. Compartilhamento e recuperação descartam navegação de callbacks obsoletos; recuperar o clipe atual mantém posição/intenção. Originais concluídos são verificados antes de serem oferecidos para recuperação. RecordingFileProvider permite somente leitura de URIs privadas confinadas a files/recordings. Publicação em Filmes/LumaCamera usa MediaStore; exige espaço temporário para duas cópias.

Captura, renderização e inferência usam trabalho fora da interface, guarda de gerações e descarte de resultados antigos. Pausa/giro/saída finalizam gravação; não há captura em segundo plano. INTERNET é removida do manifesto mesclado, inclusive quando transitiva. Modelos embarcados permitem segmentação offline. CAMERA e RECORD_AUDIO servem à captura; não há permissão de leitura da biblioteca inteira. Diagnóstico não inclui identificador pessoal. Confirmar manifesto final e comportamento permanece parte da validação.

## Próximos critérios

Os **83 contratos gráficos/LUT** passaram em WebGL/ANGLE, incluindo cores, máscara, assistência Log e cantos assimétricos em rotações/espelhamentos. A câmera sintética substitui somente o sampler externo por textura 2D; isso não testa OES/EGL Android, inferência ML Kit, decoder ou MP4. JUnit/lint/compilação integrados são registrados em [validação](../validation/VALIDACAO.md), sem inferir execução no aparelho a partir desses contratos.

Validar 0.12 no A14: proporção e orientação nas duas câmeras/retrato/paisagem, caminhos de encoder, contornos e atraso de Pessoas/Objetos, ponto de seleção e nova seleção após perda/reabertura, transição assunto/fundo, foco AF independente, v1/v2 no MP4 com assistência, LUT exportada, replay/retomada do player, biblioteca/recuperação, áudio e interrupções. Medir 720p/1080p, FPS efetivo, atualização de máscara, aquecimento e consumo em clipes longos. Depois investigar adaptação automática de custo, tracking por identidade, profundidade editável, HDR/10 bits, RAW, estabilização com rotação/rolling shutter. Nome/ícone finais, SDK de distribuição e assinatura de release permanecem pendentes.


## Estabilização digital e assistência IA — 0.8

FrameMotionEstimator compara até 35 blocos em pequenas imagens GLES de luminância, com busca limitada e consenso espacial. Estima translação X/Y e confiança; rejeita pouca textura/luz, cortes, mudanças grandes e consenso fraco. Um recorte IA válido/recente pode excluir o assunto da busca, sem iniciar inferência adicional.

StabilizationPolicy usa filtro causal com acompanhamento de panorâmicas, decaimento por confiança/idade, limites de crop e interpolação a cada frame renderizado. Copy.frag aplica zoom/deslocamento em coordenadas canônicas após a rotação UV; prévia e encoder recebem o mesmo snapshot. Não há renderização extra de um FBO de resolução completa para essa etapa. Toques aplicam a inversa do sampler publicado via objeto imutável volatile.

A função fica desligada por padrão. Quando ligada, usa readback 96/128 px e até 10/15 análises por segundo; nenhum benchmark garante essa taxa no A14. Zoom 1,04–1,25 é fixo dentro do clipe e reduz campo de visão/detalhe. Não corrige rotação, perspectiva, rolling shutter ou borrão, e não usa rede neural própria. Ver [ESTABILIZACAO.md](../guides/ESTABILIZACAO.md).


## Monitor e controles profissionais — 0.9

O shader de cópia concentra monitores em dois vec4: modos (gate da prévia, zebra, false color, contornos) e parâmetros (limiar, sensibilidade, opacidade, força Log). Histogramas/encoder/segmentação/movimento recebem gate zero. Assistência Log é zero no encoder. A análise visual de zebra/false color restaura SDR mesmo quando a exibição permanece Log; contornos aproximam o contraste SDR usando a derivada da curva. Uniforms/atributo são resolvidos e cacheados na inicialização; centro/tuning são cacheados na mudança dos ajustes.

`MonitorAnalysis` produz 64 bins por amostra RGBA 64×36 do sinal final gravado, incluindo efeitos/Log/crop. O target/readback é criado somente ao ligar o histograma. Os eventos chegam até 4 Hz e cada evento possui seu próprio array, encaminhado com geração pelo engine. `HistogramDisplayPolicy` sanitiza dados e `HistogramOverlay` guarda Paint/Path/legendas. O painel limpa leituras ao sair de estados ajustáveis ou desligar a ferramenta.

`CompositionPolicy` encaixa guias, constrói área segura e calcula horizonte pela projeção da gravidade adaptada à rotação da tela. `GridOverlay` desenha exclusivamente na interface. O listener opera até 10 Hz e é desligado na pausa; NaN indica nível indisponível. A disposição em paisagem reúne avisos IA/estabilização lado a lado e reserva uma linha para áudio/tempo/histograma, para evitar cobrir avisos.

`ProfessionalCaptureTools` calcula tempo a partir do ângulo/FPS e o limita ao sensor/período do quadro. AE/AWB usam `CapturePolicy.autoLock`, disponibilidade física e domínio automático, permanecendo independentes. `videoBitrateScale` multiplica o alvo e o encoder candidato limita à faixa AVC antes da preparação. `recordingBitrate` é alvo configurado, não medição VBR. `recordingWithAudio()` informa a configuração real do clipe à UI; `maxAmplitude` alimenta o medidor relativo somente em REC, com cancelamento/revisão para evitar callbacks antigos.

As ferramentas acrescentam custo quando usadas. A comparação de 25 arquivos com a fonte 0.8 confirma preservação de player, armazenamento, máscaras/modelos, Log/LUT, geometria e estabilização existentes. A captura recebeu controles/monitorização novos; isso exige validar sessão, desempenho e material no A14. Veja [MONITOR_PRO.md](../guides/MONITOR_PRO.md).

## Ajustes de estabilidade — 0.11

A auditoria de captura/lifecycle/armazenamento/player/UI está em [AUDITORIA_ESTABILIDADE_0.11.md](../history/AUDITORIA_ESTABILIDADE_0.11.md). `StoragePolicy` limita a gravação com margem de finalização e original/cópia; watchdog de espaço a 1Hz atua no worker. Arquivo exclusivo é reservado antes do recorder. Originais não vazios e incertos permanecem no app, com `StoredVideo.validated=false`; arquivo ativo não entra em recuperação. Publicação confere bytes, exclui trabalhos concorrentes por origem e não desfaz um commit por falha de bookkeeping.

`CameraReopenPolicy` evita reabertura redundante da sessão; comandos UI de REC/STOP ficam pendentes até sua transição. Rejeições físicas usam revisão para não restaurar uma edição antiga nem sobrescrever efeitos locais recentes. GPU agrupa renders pendentes e invalida o handle do encoder mesmo se detach falhar. Library invalida tickets de compartilhamento na pausa/navegação e deixa cópias/exportações autorizadas terminarem sem reabrir a UI anterior. O player libera retenção de tela ao concluir/interromper.

O painel Condições para gravar lê `recordingStorageSnapshot` e `SessionHealth` em thread pontual. `SessionHealthPolicy` rejeita leituras inválidas e distingue dados ausentes de bateria zero/status térmico saudável. Não há coleta por frame, nova inferência, modificação automática de qualidade ou prova de performance nativa.

## Ferramentas profissionais — 0.12

ToolCatalog é a fonte única de navegação: busca normalizada por tarefa e favoritos abrem controles existentes, com âncoras de seção. ProfessionalWorkspaceStore persiste presets, identificações, contador por cena e pontos A/B; CameraPreferences continua persistindo cada chave. Snapshot de preset aceita somente chaves de captura conhecidas e aplica limites da lente.

CubeLut valida entradas de 17 ou 33 pontos e cria atlas 2D para interpolação trilinear GLES2. copy.frag aplica LUT só quando a saída é prévia; o caminho do encoder desliga assistência, LUT e indicadores. MonitorAnalysis usa um readback de 64×36 a até 4 Hz compartilhado por histograma, waveform, RGB parade e vectorscope, antes da assistência/LUT.

SubjectTrackAnalysis escolhe componente coerente da máscara e mantém âncora; ObjectTrackingPolicy desloca seed por correspondência visual restrita antes de inferir MagicTouch. FocusTrackingPolicy suaviza/rejeita saltos e limita AF. FrameGeometry.outputGlAt e SubjectTrackDisplayPolicy desenham retículo sem nova rotação do vídeo. Acompanhamento não calcula profundidade e respeita lente manual/travada.

CameraMotionPolicy interpola zoom/foco por tempo real; a UI agenda atualizações a cada 50 ms, cancela em toque/saída/erro/reabertura e não força renderização de frames. CameraZoomPolicy calcula crop no sensor; regiões AF usam o mesmo crop.

RecordingOptions é capturado antes de REC. Segmentação usa evento MAX_FILESIZE_APPROACHING → setNextOutputFile → NEXT_OUTPUT_FILE_STARTED, mantendo gravador/superfície/relógio. Arquivos permanecem protegidos até STOP; LumaPublish inspeciona e publica fora do worker GPU. VideoStore mantém reservas/filas globais no processo e fichas privadas atômicas por nome de clipe. A duração estimada da ficha não substitui a duração do contêiner.

AudioRoutePolicy separa preferência de confirmação real. MediaRecorder routedDevice durante REC determina o rótulo, o listener trata mudanças e IDs antigos não são restaurados na abertura. Biblioteca carrega fichas no executor, exibe T/P/projeto e permite filtro/exportação.

Guia completo: [FERRAMENTAS_PRO_0.12.md](../guides/FERRAMENTAS_PRO_0.12.md). As seções anteriores registram evolução e contratos preservados; testes locais não demonstram execução nativa no A14.


## Estabilização — 0.13

A análise de movimento tem executor CPU serial próprio, uma tarefa em voo e resultados identificados por geração/timestamp. Readback GLES continua no worker de captura; luminância, matching e consenso rodam fora dele. Alterar sessão/geometria/configuração estrutural invalida amostras antigas. Força/suavidade não reiniciam a trajetória durante REC.

FrameMotionEstimate acrescenta rotationRadians da cena no espaço físico da imagem. StabilizationTransform acrescenta rotação/aspecto e centraliza sourcePoint/outputPoint, usados respectivamente por toque e retículo. copy.frag aplica a transformação após orientação: converter ao espaço físico, rotacionar, retornar ao UV e deslocar o centro. Prévia, encoder e scopes compartilham essa amostragem; LUT continua exclusiva da prévia.

StabilizationPolicy filtra translação e giros pequenos por tempo/confiança, distingue movimentos sustentados de oscilações e limita correção pelas quatro bordas do recorte fixo. Configurações independentes de modo e compensação de giro são normalizadas, persistidas e incluídas em presets. Não há buffering de vídeo futuro, nova biblioteca nativa ou uso de giroscópio. O roteiro e os limites estão em [ESTABILIZACAO.md](../guides/ESTABILIZACAO.md).
