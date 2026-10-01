# Player interno — Luma 0.11

A 0.11 mantém os backends da 0.10 e refina o lifecycle: ao concluir/interromper, o player libera a retenção de tela. Pedidos de compartilhamento são únicos e deixam de abrir a UI depois de uma pausa/navegação. Cópias/exportações já iniciadas podem terminar em rotação. Originais não validados aparecem com **Inspecionar**, sem autoplay; compartilhar bytes preservados não confirma que o MP4 está utilizável. Veja a [auditoria](../history/AUDITORIA_ESTABILIDADE_0.11.md).

Abra um vídeo pela **Galeria do Luma**. As mudanças desta etapa são no próprio aplicativo.

Os controles ficam abaixo do vídeo: **Reproduzir/Pausar/Rever**, **−5s**, **+5s**, barra de posição e **Som/Mudo**. Eles não somem ao tocar na imagem. A imagem usa proporção FIT; barras preservam o enquadramento. O arraste mostra o tempo escolhido e decodifica a nova posição ao soltar, evitando dezenas de seeks intermediários.

Quando outro aplicativo interrompe o áudio ou os fones são desconectados, o player informa o motivo. **Retomar áudio** permite tentar reproduzir novamente após a interrupção. Uma gravação sem trilha de áudio recebe aviso; o player não cria o áudio que não foi capturado.

Há uma sequência limitada de recuperação interna quando o decoder falha, a superfície não fica pronta, ou buffering, posição e frames ficam parados por muito tempo: **Media3 com SurfaceView → Media3 com TextureView → MediaPlayer do Android com TextureView**. O player preserva a posição recente e a intenção de reprodução, respeita pausa, segundo plano e interrupções de áudio. Não repete automaticamente o mesmo backend para sempre. Os três caminhos usam o arquivo original, sem transcodificar.

**Reproduzir em modo compatível** começa na alternativa TextureView/Media3, usando fila síncrona de codec e o workaround de edit lists do extrator MP4; pode continuar para o MediaPlayer se necessário. Isso muda a leitura do contêiner e não o arquivo. Edit lists legítimas podem conter ajustes de áudio/tempo: o workaround é uma alternativa de recuperação, não uma promessa de sincronismo universal. Não há exigência de decoder de software ou de player externo.

O caminho MediaPlayer prepara o arquivo de forma assíncrona, controla a superfície por geração e serializa seeks. Se um erro invalida os getters do Android, usa a última posição saudável. Ao perder foco de áudio definitivamente ou desconectar fones durante o arraste, soltar a barra não reativa o som silenciosamente. Ações explícitas **Reproduzir** e **Retomar áudio** permitem retomar.

A duração vem do backend ativo quando disponível. A biblioteca consulta metadados fora da interface quando o MediaStore ainda não tem duração. **—:—** indica duração indisponível; **0:00** é posição inicial, não duração inventada. Consulte [DURACAO_E_GRAVACAO.md](../development/DURACAO_E_GRAVACAO.md) para o novo relógio de captura. A barra usa escala normalizada, com unidades em milissegundos e apresentação de horas para clipes longos.

Se voltar a travar ou perder áudio no A14, abra **⋮ → Salvar diagnóstico de reprodução**. O JSON registra os eventos realmente observados: codecs, trilhas, interrupções, erros, underruns de áudio, frames descartados, posição e buffering. Não contém imagens/áudio do vídeo. Esse arquivo permite diferenciar defeito na gravação, decoder, leitura ou interrupção de áudio.

A 0.10 também corrige relógio e finalização de gravações novas. Arquivos antigos com timestamps inválidos podem continuar problemáticos; não são regravados automaticamente. O problema relatado ainda precisa ser verificado no A14 com clipes novos e antigos; testes JVM não executam os codecs Samsung nem o AudioTrack Android. A validação disponível está em [VALIDACAO.md](../validation/VALIDACAO.md).

