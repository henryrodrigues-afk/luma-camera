# Histórico de mudanças

## 1.0.0 — 01/10/2026

Esta versão reúne correções de captura, armazenamento, reprodução e acompanhamento local. As evidências automatizadas e físicas são registradas separadamente na [validação](docs/validation/VALIDACAO.md). **A fonte final passou com 383 testes JVM, 117 contratos gráficos e zero erros de lint. No A14, o APK final aplicou Natural/AE, gravou um novo clipe frontal Log de 25,2 s e reproduziu até o fim; áudio, timestamps e decodificação integral passaram.** O celular estava apontado para o teto, sem pessoa: qualidade de recorte, movimento e uso diário completo continuam pendentes. Ensaios de três partes pertencem ao baseline anterior, identificado na validação.

### Captura, áudio e preservação de arquivos

- O cálculo de espaço separa as cópias pendentes para a galeria do limite do arquivo que está sendo gravado. A recuperação manual também reserva espaço para sua cópia.
- A reserva de publicação diminui conforme os bytes são escritos com sucesso. A cópia rejeita arquivos que encolhem, crescem ou falham durante a transferência, preservando o original e removendo o destino incompleto antes de torná-lo público.
- A publicação mantém uma reserva de 96 MiB para finalização. Uma falha de publicação preserva o original não vazio para inspeção ou nova tentativa.
- Eventos de troca de parte recebidos no worker do gravador são tratados diretamente, evitando que um segundo agendamento permita a uma parada ultrapassar o evento. Quando uma troca de parte não foi confirmada, a duração estimada fica desconhecida em vez de atribuir o tempo de duas partes a um arquivo.
- A ficha de cada parte confirmada registra a rota de áudio observada no seu encerramento. Esse registro não substitui a verificação do som gravado.
- A biblioteca e a recuperação manual verificam a duração do original contra `elapsedEstimateMs`, quando essa estimativa existe. Uma nova tentativa de salvar não contorna a proteção da linha do tempo.
- Uma falha temporária na sondagem nativa de metadados/amostras pode ser tentada novamente após 10 segundos, ao atualizar a biblioteca. Uma linha do tempo efetivamente reprovada continua reprovada; a falha temporária não fica registrada para sempre como um original inválido.

### Player e biblioteca

- O player nativo não retoma áudio durante o arraste da barra quando recebe ganho de foco de áudio ou uma nova superfície de vídeo.
- Uma falha de busca mantém a posição solicitada. A leitura de metadados contém também falhas na criação do parser e descarta resultados de uma geração encerrada.
- A restauração procura primeiro a URI e depois o nome único da gravação. Isso preserva a seleção quando a publicação troca a URI privada por uma URI da galeria, sem escolher arbitrariamente entre nomes duplicados.
- A biblioteca observa mudanças da galeria enquanto está visível e agrupa pedidos de atualização. Atualizar a lista libera também um pedido de compartilhamento cancelado, evitando um botão sem resposta até a próxima navegação.
- Aviso e filtro de projetos ficam na área rolável. Cabeçalhos, etiquetas e controles de reprodução usam limites adequados a janelas curtas, estreitas e fontes grandes.
- O indicador superior de resolução/FPS mantém descrição acessível para abrir o formato de gravação, com texto limitado à largura disponível.
- A biblioteca continua permitindo inspeção e compartilhamento de originais não validados, sem iniciar sua reprodução automaticamente ou anunciar uma duração confirmada.

### IA, estabilização e controles

- Foco oferece **Ajuste automático · Pessoas/Objetos** com Natural, Equilibrado e Forte. A ação prepara intensidade, contorno e acompanhamento em análise Leve, desliga o oval e mantém foco manual/trava, WB, Log, estabilização e a chave de Cinema. Objetos ainda exigem seleção por toque; não foi acrescentado um detector de objetos ou medição de profundidade.
- Intensidade do desfoque fica visível, com estabilidade, bordas e qualidade em refinamentos recolhíveis. Cada controle continua independente depois do ajuste automático.
- **Ajustar exposição automaticamente** libera AE, retorna ISO/obturador ao automático, desliga ganho digital e aplica viés moderado próximo de −1/3 EV quando a lente suporta. Não muda WB/Log/foco nem recupera realces já cortados.
- Valores iniciais de ganho/temperatura são zero e contraste/saturação são 1. Preferências explícitas existentes são preservadas.
- A análise de pessoas usa a máscara nativa do modelo, com conversão de pixels e filtro temporal fora do worker de renderização. Continua havendo uma imagem em análise por vez, sem fila de quadros antigos.
- Máscaras de pessoas perdem força após 250 ms e expiram aos 600 ms; objetos perdem força após 450 ms e expiram aos 1.200 ms, contando desde a captura. Isso inclui o tempo gasto na inferência, sem anunciar uma latência garantida.
- O filtro temporal responde a movimento local das bordas, inclusive quando duas pessoas se deslocam em sentidos opostos sem mudar o centro global. Oscilações pequenas continuam sendo suavizadas.
- O blur da IA usa amostras da região desfocada e normalização por suporte para reduzir contaminação das cores do assunto no fundo. Regiões sem suporte suficiente preservam a imagem original. Isso não cria profundidade nem repara uma máscara ruim.
- Desfoque oval e IA têm raios separados; uma intensidade alta no oval não aumenta a força configurada do blur da IA. Usar ambos exige processamento adicional.
- O centro usado para acompanhar um alvo permanece no componente conectado selecionado, inclusive quando um objeto em forma de anel envolve outro objeto ou pessoa.
- O rastreamento visual de objetos exige uma região de referência inteiramente apoiada pela máscara. Bordas sem textura interna confiável são rejeitadas para reduzir o risco de acompanhar o fundo.
- Mudanças de zoom invalidam a referência da análise de movimento anterior, preservando a correção exibida e sua transição suave.
- O limite de 24 presets recusa um novo nome sem apagar o preset mais antigo. Substituir um nome existente continua permitido; a interface informa quando o limite impede o salvamento.
- Restaurar os controles cancela movimentos A/B em andamento. A leitura de LUTs de prévia usa um executor serial com fila limitada, reduzindo trabalho simultâneo em trocas rápidas.

### Distribuição e limites

- O helper Android de QA aceita screenshots PNG maiores e transfere fixtures binárias com verificação de bytes. Sua sintaxe JavaScript foi verificada; o helper público não foi executado em emulador nesta revisão.
- Identidade de versão `1.0.0`, código Android `100`, mínimo Android 10/API 29 e alvo API 34. A inspeção dos dois APKs finais confirmou entrega ARM64, 13 assets próprios idênticos à fonte, release não depurável e ausência de permissão de internet. Hashes, tamanhos e assinatura foram conferidos.
- A configuração release aceita assinatura pelas quatro variáveis `LUMA_SIGNING_*`; sem elas, o release é não assinado. Chaves e senhas não integram a fonte.
- A redução de código da release permanece desativada nesta versão para preservar os pontos de entrada nativos dos modelos.
- LumaLog continua sendo um perfil simulado sobre SDR de 8 bits. IA/desfoque/rastreamento permanecem experimentais; estabilização usa recorte e não corrige rolling shutter ou borrão.
- As correções do player não reparam automaticamente MP4s antigos com uma linha do tempo inválida. O baseline de três partes confirmou timelines/decodificação, mas não comprova junção sem lacuna, todos os codecs/fallbacks, áudio externo, temperatura ou fluidez sustentada.

## Versões anteriores

O histórico técnico das versões anteriores está no [índice de documentação](docs/README.md), incluindo as validações [0.13](docs/history/VALIDACAO_0.13.md), [0.12](docs/history/VALIDACAO_0.12.md) e [0.11](docs/history/VALIDACAO_0.11.md). Evidência de uma versão anterior não vale como nova aprovação.
