# Auditoria de estabilidade — Luma 0.11

Data: 01/10/2026. Base auditada: 0.10.0-reliable; candidata resultante: 0.11.0-stability. Objetivo: gravar, salvar e assistir no dia a dia, com controles previsíveis e recuperação de falhas. A candidata ainda não recebe o rótulo de versão estável: falta execução no Galaxy A14 5G.

## Achados e ajustes

P1 significa risco alto de interromper gravação ou perder material; P2 significa problema relevante de fluxo, disponibilidade ou consumo. Os achados abaixo vêm da inspeção de código, não de crashes reproduzidos no telefone.

| Área | Prioridade e situação concreta | Ajuste desta etapa |
| --- | --- | --- |
| Início de gravação | P1: dois toques antes do callback STARTING podem repetir pedidos ou o diálogo de microfone | Trava do pedido na UI, diálogo único e liberação ao receber resposta/pausar |
| Retomada da câmera | P1: open sempre fecha a sessão atual, e uma retomada pode solicitar a mesma câmera novamente | Identidade da superfície/lente/modo/rotação evita reabertura redundante; alterações explícitas invalidam a identidade |
| Armazenamento | P1: reserva inicial não acompanha consumo de espaço por outras gravações/apps durante REC | Verificação de espaço a 1 Hz no worker; limite considera original, cópia para a galeria e finalização |
| Preservação | P1: falha no parser pode fazer o app descartar um original não vazio | Descarte automático restrito a arquivo vazio; originais incertos ficam disponíveis com rótulo de não validado |
| Publicação | P1/P2: recuperação em mais de uma Activity pode copiar o mesmo original; cópia parcial não deve virar vídeo público | Exclusão por origem no processo, bloqueio de arquivos ativos, conferência de bytes e espaço antes de liberar IS_PENDING |
| Nome do arquivo | P1: mudança do relógio pode repetir o nome de um take anterior | Arquivo exclusivo reservado atomicamente antes do recorder |
| GPU/superfície | P2: falha em makeCurrent durante detach não deve deixar handle de encoder anexado | Limpeza do estado/handle mesmo no caminho de falha |
| Fila de frames | P2: callbacks acumulados durante publicação podem gerar trabalho obsoleto | Agrupamento limita a um render pendente e usa a imagem atual |
| Player/tela | P2: intenção playWhenReady pode continuar true ao terminar o vídeo e segurar a tela ligada | Retenção condicionada ao estado ativo e às condições de reprodução |
| Compartilhamento | P2: toques repetidos e callback atrasado podem abrir choosers duplicados ou após navegação/pausa | Ticket do pedido e invalidação pelo lifecycle |
| Trabalhos autorizados | P2: interromper executor durante rotação pode cancelar cópia/exportação em progresso | Shutdown gracioso para operações iniciadas, com callbacks obsoletos sem reabrir a UI |
| Controles físicos | P2: rollback atrasado de um pedido rejeitado pode sobrescrever edição mais recente | Revisão de submissões; apenas o pedido ainda atual pode restaurar controles |

Os detalhes finais, compilação e testes estão em [VALIDACAO.md](VALIDACAO_0.11.md). As políticas puras verificam regras; codecs, EGL, Camera2 e MediaStore exigem confirmação nativa.

## Ferramenta acrescentada para o uso diário

**Gravação → Condições para gravar** reúne uma leitura pontual de bateria, estado térmico do Android, espaço disponível e estimativa de tempo pelo bitrate. O cálculo de espaço considera duas cópias e reserva de finalização. A estimativa não é cronômetro garantido: VBR, contêiner e driver variam. Atualizar a leitura não altera a qualidade, os efeitos ou a resolução.

Temperatura, quando disponível, é a leitura da bateria. O estado térmico é o alerta do sistema, não uma medição da temperatura da câmera. Dados ausentes ficam indisponíveis. O painel consulta sob ação do usuário, sem inferência adicional ou polling por frame. O watchdog de espaço da gravação é separado e opera no worker. A margem não reserva bytes de forma exclusiva: consumo abrupto por outro app ainda pode vencer a proteção.

## Áreas revisadas e preservadas

- Geometria compartilhada de prévia/encoder/toque, origem monotônica do gravador e contador da 0.10.
- Recuperação interna limitada do player, preparação assíncrona, geração de callbacks, seek e interrupção de áudio.
- Preferências saneadas, chaves individuais e restrições de câmera durante cada clipe.
- Modelos embarcados, uma inferência em voo, expiração de máscaras, refinamento temporal e recorte limitado da estabilização.
- Log/LUTs e assistência de prévia, monitores fora do MP4 e bitrate limitado ao encoder.
- Provedor de compartilhamento somente leitura, caminho canônico confinado, receiver interno e ausência de rede no manifesto.

Não foi identificado um novo defeito concreto em Log/geometria/máscara que justificasse substituir esses algoritmos nesta etapa. Isso não comprova sua qualidade no A14. Desfoque de objetos, detalhes finos, movimento rápido e estabilização de rotação continuam limitados.

## Próximos escopos e ferramentas

| Ordem | Escopo proposto | Benefício e critério de entrada |
| --- | --- | --- |
| 1 | Validação nativa, diagnóstico de captura e reconciliação após crash | Medir clipes reais, falhas, cadência/áudio/retomadas; auditar linhas MediaStore pendentes após encerramento do processo e preservar originais antes de qualquer limpeza |
| 2 | Presets pessoais com importação/exportação | Trocar setups com ação explícita; validar limites da lente e nunca restaurar um alvo de objeto antigo |
| 3 | Waveform de luminância e RGB parade | Conferir exposição e canais usando a amostra já lida para monitor; limitar atualização e medir custo no A14 |
| 4 | Vetorscope e guias de cor | Apoiar balanço/saturação; declarar SDR/Log e domínio de análise, sem anunciar calibração inexistente |
| 5 | Foco assistido e rack focus com marcadores A/B | Transições repetíveis; separar foco da lente e máscara digital, indicar indisponibilidade física |
| 6 | Gestão de takes e metadados de edição | Nome de projeto/cena/take, perfil/força Log, LUT correspondente e exportação de ficha do clipe |
| 7 | Zebra/peaking/false color com presets | Melhorar descoberta e configuração rápida, preservando chaves independentes e exclusão do MP4 |
| 8 | Otimização térmica opcional | Depois de medir, oferecer perfis de custo escolhidos pelo usuário; evitar mudança silenciosa no material durante REC |
| 9 | Profundidade/tracking e estabilização avançada | Projeto separado: modelos/dados, hardware, latência, qualidade e bateria; alto risco para uma versão diária leve |
| 10 | Distribuição estável | Assinatura de lançamento preservável, revisão do SDK exigido, R8/JNI, notices e instalação/atualização sem perda |

Waveform, RGB parade, vetorscope, presets e rack focus são propostas de próxima etapa; não aparecem como recursos implementados na 0.11. Desbloquear resolução/FPS não expostos, recuperar alcance dinâmico ausente ou transformar SDR em RAW/Log nativo não são promessas de versão estável.

## Critérios para chamar a versão de estável

1. Nenhum bloqueador conhecido de gravar/salvar/abrir/reproduzir; toda falha deve apresentar uma ação compreensível e preservar material não vazio.
2. Matriz física no A14: duas câmeras, vertical/horizontal, modos realmente disponíveis, sem áudio/com áudio, efeitos individuais e conjuntos. O mesmo material deve ser legível no player interno e em um segundo leitor.
3. Sessões repetidas: 20 clipes curtos; clipes de 2 e 10 minutos; 20 abrir/fechar/retomar/trocar câmera. Verificar arquivos completos, tempo, frames, áudio e consumo. São metas de teste, não resultados desta auditoria.
4. Interrupções: permissão negada, chamada/outro áudio, fones, pausa/retorno, rotação, bloqueio de tela e pressão de espaço. Encerramento do processo durante REC pode deixar MP4 sem índice; preservar bytes não equivale a reparar o arquivo.
5. Layout: fonte normal/ampliada, barras por gestos/botões e área pequena; nenhuma sobreposição deve impedir Gravar/Parar/Galeria/menus. Recuperações devem manter posição/intenção e evitar áudio inesperado.
6. Qualidade: monitor/guias ausentes do MP4, LUT compatível com perfil/força, duração coerente com o cronômetro, orientação correta e sincronismo de áudio medido. Registrar taxas/latência reais, não usar FPS solicitado como FPS obtido.
7. Desempenho: comparar baseline sem efeitos e com cada ferramenta; registrar temperatura/alertas, bateria, frames perdidos e memória. Sem limites de desempenho inventados antes das medições.
8. Atualizar por cima com o mesmo certificado e preferências/galeria preservadas; release/R8 só após o mesmo percurso de testes na versão que será distribuída.

O roteiro operacional está em [TESTE_DIARIO_A14.md](../validation/TESTE_DIARIO_A14.md). Compilação/JVM/GLSL são uma barreira de regressão útil, mas não substituem esses critérios.

## Referências primárias consultadas

- [CameraDevice.StateCallback](https://developer.android.com/reference/android/hardware/camera2/CameraDevice.StateCallback): desconexão, prioridade e erros da câmera.
- [MediaRecorder.setMaxFileSize](https://developer.android.com/reference/android/media/MediaRecorder#setMaxFileSize(long)): limite e parada assíncrona, com callback de informação.
- [PowerManager.getCurrentThermalStatus](https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus()): estado térmico do sistema, disponível no SDK mínimo desta base.
- [Armazenamento específico do app](https://developer.android.com/training/data-storage/app-specific#query-free-space): disponibilidade de espaço e falhas de gravação.
- [Android: análise de crashes](https://developer.android.com/topic/performance/issues/crash): diagnóstico e testes de estabilidade em execução real.

As regras de reserva, limites e classificação acima são decisões próprias do projeto; as referências não constituem benchmarks do Luma ou do A14.
