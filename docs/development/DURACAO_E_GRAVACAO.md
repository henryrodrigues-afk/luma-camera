# Duração e finalização — Luma 0.10

O tempo de um frame de câmera (`SurfaceTexture.timestamp`) pode ter uma origem diferente do tempo esperado pelo gravador. Usar esse valor diretamente em `eglPresentationTimeANDROID` pode misturar a base do sensor com a base monotônica do áudio/MediaRecorder. Isso é uma causa possível de duração estranha, longos intervalos vazios e dessincronização; o clipe problemático do A14 não foi fornecido nesta etapa.

A 0.10 usa `System.nanoTime()` no PTS e no pacing do encoder, preservando a origem absoluta CLOCK_MONOTONIC esperada pelo gravador. O timestamp da câmera passa a servir somente para identificar callbacks duplicados, aceitando uma câmera que reinicie sua própria contagem. O app não usa FPS × número de quadros para fabricar duração e não acelera a cena quando a GPU perde frames. Gaps de processamento conservam seu tempo real. Isso não certifica latência audiovisual no aparelho; ela ainda deve ser medida.

O contador da interface consulta `recordingElapsedMs()` do engine, ancorado ao início confirmado do gravador. A atualização da UI deixa de decidir a origem do contador. O relógio usa milissegundos no display, enquanto EGL recebe nanossegundos e codecs os convertem para microssegundos. O pacing rejeita timestamps duplicados depois dessa conversão.

Ao parar, o worker suspende novas submissões e mantém a superfície EGL viva até o gravador finalizar o MP4. Depois libera a superfície/recorder. Um erro de finalização preserva o original quando há samples de vídeo recuperáveis. Uma falha posterior ao start também não apaga esse material automaticamente.

Antes de publicar uma nova gravação, o app lê dimensões/duração e verifica que há um primeiro sample de vídeo. Quando o tempo real de gravação é conhecido, aplica limites inferior/superior com tolerância para startup, drain e arredondamento. Um arquivo com tempo incompatível fica preservado para diagnóstico em vez de receber duração inventada.

O MediaStore indexa DURATION/SIZE/WIDTH/HEIGHT como colunas somente leitura. A base não tenta forçá-las: publica IS_PENDING=0 e consulta o scanner normalmente. Se a consulta ainda retornar zero/NULL, lê os metadados do MP4 em worker e usa um cache compartilhado limitado a 64 entradas. A galeria mostra **—:—** quando a duração é desconhecida; o player mostra sua duração lida pelo extractor/backend ativo. O menu Informações consulta o player aberto antes de usar os dados antigos da lista.

Para conferir: grave um clipe novo de cerca de 10 segundos, com áudio e sem áudio, nas duas câmeras. Compare contador, duração da galeria e reprodução do começo ao fim. Repita com efeitos ativos para avaliar frames perdidos, sincronismo, aquecimento e qualidade. Um clipe antigo com linha do tempo já corrompida pode continuar inválido mesmo após atualizar o app; o original permanece intacto e o menu ⋮ permite exportar diagnóstico da tentativa de reprodução.
