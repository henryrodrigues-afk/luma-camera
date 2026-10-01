# Luma Camera 1.0 — ferramentas profissionais e navegação

Base para Android 10+, com alvo inicial Galaxy A14 5G. Os recursos abaixo estão implementados; os cenários físicos já executados e os ainda pendentes estão separados na [validação](../validation/VALIDACAO.md). A base continua MP4 H.264 SDR de 8 bits com AAC opcional. O build final passou; aplicação física dos novos ajustes e percursos completos continuam separados como pendentes.

## Encontrar e organizar funções

Abra a engrenagem: **Ferramentas** reúne funções por tarefa. A busca entende termos como “microfone externo”, “exposição”, “Log”, “zoom” e “RGB”. Um resultado abre a categoria e rola até a seção correspondente; não cria uma cópia do controle.

Use **★** para escolher até três atalhos da tela da câmera. Desmarque uma estrela antes de substituir um atalho ocupado. O acesso inicial é Presets / Foco / Zoom. O formato de gravação permanece acessível pelo indicador de resolução/FPS. A biblioteca abre pelo ícone Galeria. Nos painéis, toque no título para retornar à central.

Controles de áudio ficam em **Áudio**, exposição em **Exposição**, ferramentas de leitura em **Monitores** e identificação em **Projetos**. Cor e Log compartilham **Imagem**; ajustes criativos ficam em uma expansão. Cada ferramenta de monitoramento mantém sua própria chave. Mudanças que exigem outro formato, lente ou configuração de gravador são bloqueadas durante REC.

## Presets personalizados

**Presets → Salvar meus ajustes como preset** guarda controles, cor, monitores, formato, áudio ligado/desligado, LUT selecionada e pontos A/B. Aplicar é uma ação explícita. Os controles físicos são adaptados à lente atual; se o formato salvo não existe nela, o formato atual é preservado. A aplicação informa essa adaptação.

Alvos de IA, pontos temporais de toque e uma trava adquirida não são restaurados como se pertencessem à nova cena. Selecionar um preset não troca o projeto/cena/tomada nem escolhe um microfone externo antigo. Até 24 presets locais; salvar o mesmo nome substitui sua combinação. Os pontos de partida Original / Edição Log são ações separadas dos presets pessoais.

## LUT de prévia e sinal de entrada

**Imagem → LUT na prévia → Importar LUT .cube** importa uma cópia privada, sem depender da URI nas próximas sessões. Aceita LUT 3D de **17 ou 33 pontos**, domínio de entrada e força independente. Limites: 4 MiB e 24 LUTs. Arquivos incompletos, tamanho não suportado, valores inválidos e LUT 1D são recusados.

**Converter Log para SDR na tela** define o sinal que chega à LUT:

- Para um look criativo que espera SDR, use a conversão SDR quando estiver gravando Log.
- Para uma LUT que já converte LumaLog em SDR, deixe a conversão desligada para evitar dupla conversão.
- Com Log desligado, a entrada é o SDR original processado pelos efeitos ativos.

O menu informa a entrada efetiva. Uma LUT de Apple Log, S-Log ou outro perfil não passa a corresponder ao LumaLog por ter extensão `.cube`. A LUT importada afeta **somente a tela**. Perfil Log e outros efeitos de gravação seguem entrando no arquivo conforme suas próprias chaves. Histograma e scopes continuam analisando o sinal gravado.

A LUT técnica deve corresponder à **versão e força do LumaLog**. A exportação com força parcial gera uma LUT de **65 pontos**, destinada ao editor externo e recusada pela importação de prévia, que aceita 17/33. Para visualizar essa gravação convertida no app, use **Converter Log para SDR na tela**. A exportação com força de 100% usa 33 pontos e pode ser importada na prévia para a mesma versão do perfil.

## Waveform, RGB parade e vectorscope

Em **Monitores**, ligue cada chave individualmente. Waveform mostra luminância por posição horizontal; RGB parade separa canais; vectorscope mostra crominância Cb × Cr. A análise usa o sinal final destinado ao arquivo, antes da assistência SDR, LUT e indicadores visuais.

Os três instrumentos e o histograma compartilham uma leitura reduzida **64×36, até 4 Hz**. São aproximações do sinal SDR codificado, sem calibração de instrumento externo ou histograma RAW. Em Log, sua leitura também permanece em Log. Os painéis aparecem na câmera quando cabem sem cobrir o HUD; em janelas curtas, use o painel Monitores. Não são gravados, mas têm custo de processamento que precisa ser medido no A14.

## Zoom e foco A/B

**Zoom** permite ajuste atual, pontos A/B, duração e execução A→B ou B→A. A interpolação smoothstep usa tempo real, sem depender da quantidade de quadros renderizados. **Parar** interrompe na posição atual. O recorte usa limites anunciados pela câmera; não acrescenta resolução ou detalhe óptico.

**Foco → Foco A/B** memoriza duas distâncias da lente, duração e direções. Atual→A / Atual→B usa o valor manual ou a distância informada pelo sensor. Sem resultado real, não inventa uma distância. Executar A/B ativa foco manual e exige suporte físico da lente; uma lente sem esse controle mostra o motivo e mantém os botões indisponíveis. Desfoque/Cinema são efeitos digitais separados.

Toque na prévia, ajuste manual, outra execução, saída do app, reabertura e rejeição de ajuste cancelam o movimento em curso. As operações podem ser usadas durante REC quando a lente permite.

## Trava de foco e acompanhamento por IA

**Travar foco da lente** mantém a distância alcançada: em hardware manual disponível, usa a posição reportada; em outras lentes com AF por toque, mantém aquisição/lock pelo sensor. A confirmação vem de Camera2, nunca da existência de uma máscara. Foco manual já mantém o valor escolhido. **Voltar ao foco automático** libera manual/trava e retorna ao AF habitual.

**Acompanhar assunto por IA** funciona com ou sem desfoque. Escolha Pessoas ou Objetos e toque no alvo. O retículo mostra o ponto acompanhado na prévia, respeitando orientação, espelhamento frontal e recorte de estabilização. Com lente automática e regiões AF disponíveis, o app atualiza a área de foco; com manual ou trava, continua acompanhando visualmente e conserva a distância fixa.

Para objetos, correspondência visual de uma pequena região em baixa resolução desloca a seleção antes da inferência MagicTouch. Contraste, confiança, ambiguidade e intervalo entre frames determinam se a seleção continua segura. Perda, oclusão ou salto exigem novo toque. Para pessoas, um componente coerente da máscara é acompanhado, evitando um centro entre duas pessoas afastadas. Pessoas sobrepostas ainda podem formar um único componente. Não existe reconhecimento persistente de identidade nem medição de profundidade.

A área da lente recebe suavização, limite de frequência e rejeição de grandes saltos. AF contínuo é preferido para evitar reiniciar aquisição repetidamente. Uma câmera sem regiões AF pode mostrar acompanhamento visual sem deslocar o foco físico; isso depende das capacidades reais do aparelho.

### Ajustes automáticos de desfoque e exposição

Em **Foco → Desfoque por IA**, **Ajuste automático · Pessoas/Objetos** oferece Natural, Equilibrado e Forte. A ação prepara intensidade, contorno, análise Leve e acompanhamento; preserva foco manual/trava, Log, balanço de branco, estabilização e a chave de Cinema. Pessoas são recortadas automaticamente; objetos continuam exigindo um toque dentro do alvo. A intensidade permanece visível e os detalhes ficam em **Refinamentos do desfoque**. Veja os valores e limites no [guia de foco e desfoque](FOCO_E_DESFOQUE.md).

Em **Exposição**, **Ajustar exposição automaticamente** retorna ISO/obturador ao automático, libera AE, desliga ganho digital e usa EV moderado quando suportado. Não altera Log, WB ou foco nem garante recuperação de realces. Veja [Monitor Pro](MONITOR_PRO.md). Ambos são ajustes aplicados uma vez; escolhas individuais posteriores continuam valendo.

## Microfone externo

**Áudio → Entrada preferida** lista entradas conectadas anunciadas pelo Android, incluindo USB/com fio quando disponíveis. Automática permite ao sistema escolher. **Rota realmente usada** consulta o gravador durante REC; preferência aceita não significa rota confirmada. Entrada ausente/rejeitada é informada, e a rota efetiva fica registrada na ficha.

IDs de dispositivos pertencem à conexão atual. Ao abrir o app, a entrada retorna para Automática, evitando restaurar um ID que agora identifica outro acessório. Escolha novamente o microfone conectado. Faça um clipe curto e confira áudio no player. Não foi adicionado processamento de ganho, redução de ruído ou monitoramento por fone.

## Projetos, cenas e tomadas

**Projetos** define projeto, cena e próxima tomada. O contador avança somente quando REC realmente começa. Partes da mesma sessão mantêm o número da tomada. A biblioteca exibe identificação e permite filtrar por projeto. No menu do vídeo, **Exportar ficha da tomada** salva o JSON associado àquele clipe.

A ficha privada acompanha o nome imutável do clipe mesmo depois de publicar o MP4 na galeria. Ela guarda identificação, número de parte, rota de áudio e configurações solicitadas; não substitui metadados reais de duração/exposição da câmera. **Exportar ficha dos ajustes atuais** cria um retrato da configuração atual. Ao desinstalar o app, suas fichas e configurações privadas podem ser removidas; exporte as fichas que deseja levar para outro editor.

## Gravações longas em partes

**Gravação → Gravações longas em partes** ativa segmentação e escolhe tamanho alvo, entre 64 e 3072 MiB. Comece em 64 MiB para testar a troca. A API nativa troca o arquivo de saída sem reiniciar o mesmo MediaRecorder; a troca só é contabilizada depois do evento de início da próxima saída.

Por contrato da API, as saídas ficam protegidas durante REC e só são lidas/publicadas depois de parar o gravador. O contador principal mede a sessão inteira; cada MP4 mantém a duração real que o contêiner informa. Os originais são publicados em uma fila separada, sem copiar gigabytes no worker de renderização. Reservas incluem originais, cópias pendentes e margem de finalização.

Sem próxima saída ou espaço suficiente, a sessão encerra com segurança. Encerramento abrupto pode deixar a última parte incompleta; partes anteriores não vazias são preservadas para inspeção/recuperação. Preservar o arquivo não repara automaticamente um MP4 sem índice. O baseline real no A14 produziu três partes com timelines e decodificação verificadas; isso não comprova junção sem lacuna, sincronismo acústico ou sessões extensas. Veja a [validação](../validation/VALIDACAO.md).

## Referências de navegação e contratos

Consulta em 01/10/2026. Implementação própria, sem copiar interface ou código de aplicativos comerciais.

- [Blackmagic Camera](https://www.blackmagicdesign.com/products/blackmagiccamera/) e [especificações oficiais](https://www.blackmagicdesign.com/products/blackmagiccamera/techspecs): inspiração para acesso direto aos controles, monitoramento e LUTs.
- [RED: Rack Focus](https://docs.red.com/955-0127_v7.4/Raven-Operation-Guide/en-us/Content/5_Advanced_Menus/6_Focus/Rack_Focus.htm): referência de pontos memorizados, velocidade e acesso direto à transição de foco.
- [Android MediaRecorder](https://developer.android.com/reference/android/media/MediaRecorder): preferência/rota de áudio, limite, próximo arquivo e ciclo de finalização.
- [Camera2 CaptureRequest](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest): recorte de zoom, regiões de medição e modo/trigger de foco conforme suporte da câmera.
- [Exemplos oficiais Android no GitHub](https://github.com/android/camera-samples): referência de descoberta de capacidades e controles de câmera.
