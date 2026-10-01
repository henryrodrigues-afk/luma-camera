# Design e interação — Luma Camera 0.13

A 0.11 manteve a composição e adiciona travas de pedidos de gravação/permissão/reabertura, identificação do áudio real do clipe e painel de condições com atualização explícita. Arquivos não validados têm indicação e ação Inspecionar. As mudanças priorizam previsibilidade; não acrescentam animação por frame ou reduzem a qualidade silenciosamente. A [auditoria](../history/AUDITORIA_ESTABILIDADE_0.11.md) separa os ajustes implementados das próximas propostas.

## Navegação e ferramentas — 0.12

A engrenagem abre uma central por tarefa, com busca sem distinção de acentos, favoritos e categorias. Uma função aparece uma vez no catálogo; atalhos e resultados levam à mesma chave/valor. Busca com várias palavras usa título, descrição e sinônimos. Resultados abrem a seção relevante, preservando os controles da categoria.

Três favoritos ocupam os atalhos existentes, sem acrescentar outra faixa ao dock. Rótulos curtos, ícones vetoriais por tipo e descrições de acessibilidade identificam o destino. Painel usa Ferramentas/Presets/Foco/Monitores como acessos frequentes; as outras categorias ficam na central. O título permite voltar para a busca. Áudio e projetos têm categorias próprias; o botão da grade e o chip de formato permanecem acessíveis na câmera.

Trava, manual e acompanhamento visual têm comportamento explicado. Foco A/B físico indisponível apresenta motivo, sem um botão que promete profundidade. LUT informa seu sinal de entrada e restrição à tela. Scopes usam o espaço medido entre os HUDs; em janelas menores passam para o painel. O player/biblioteca conservam sua hierarquia e adicionam identificação/filtro/ficha sem alterar seus backends.

A inspeção é de fontes e políticas. Não foi renderizada uma tela Android nativa desta versão; teclados, fonte grande e bordas precisam de conferência no A14.

## Correções de disposição na 0.10

A área útil considera barras do sistema, recorte e teclado. O dock lateral só aparece quando a janela é larga o suficiente; em paisagem curta os atalhos compactos têm rolagem. Fonte ampliada empilha rótulos e valores e usa altura de conteúdo nos botões, preservando alvos de toque. O painel se dimensiona pela janela, conserva a posição na mesma página e volta ao topo ao trocar de categoria.

Histograma e áudio usam o espaço entre os HUDs. Quando não cabem, o painel Monitor Pro mantém as leituras e uma ação abre esse painel se houver área para ela. Se os próprios HUDs mais o contador excederem a prévia, leituras redundantes se recolhem, preservando contador e mensagem relevante. A estimativa do HUD expandido independe de visibilidade para evitar alternância de esconder/mostrar. Ajustes de foco, estabilização, imagem e monitor continuam nos menus. O player preserva controles abaixo do viewport e permite rolagem em espaços menores. Ações da biblioteca se empilham conforme largura/fonte. A inspeção do código e as políticas testadas não substituem uma captura visual nativa no A14.

O app usa superfícies em carvão, texto claro e verde-lima para controles ativos. A câmera permanece o centro da experiência. Os componentes são views Android e vetores desenhados em código; o visual não acrescenta fotografias, fontes baixadas, bibliotecas de animação ou blur de interface ao APK.

## Câmera e ajustes

- Marca Luma compacta, resolução/FPS acessíveis no topo, leitura de ISO/obturador e estado da sessão.
- Indicadores de Log/efeitos com largura limitada; lente e máscara IA ficam em um grupo vertical, evitando a sobreposição dos avisos anteriores. Erros continuam visíveis.
- Três atalhos escolhidos pelo usuário; Galeria e Inverter têm ícone e legenda. Obturador de 88 dp, com quadrado de parada durante gravação.
- Cartões com contorno discreto, switches de pelo menos 56 dp e sliders com valor separado em texto monoespaçado. Ícones/botões têm alvo de 48 dp; os rótulos principais usam controles nativos acessíveis.
- Painel inferior em retrato, lateral em paisagem. Menus e biblioteca usam rolagem; os formatos FIT de câmera e reprodução permanecem.
- Feedback de toque por RippleDrawable nativo. A entrada do painel dura 160 ms e respeita o desligamento das animações do sistema; não é executada durante gravação. Não há loops decorativos, brilho animado, blur ou animação por frame da câmera.

## Trabalho removido da interface

Os valores dos sliders continuam sendo enviados imediatamente ao engine; apenas a gravação das preferências espera 250 ms sem novo movimento. Soltar o slider, fechar o painel e pausar o app concluem a persistência. Durante arraste, atualizamos rótulo/HUD necessário sem percorrer todos os controles. Textos, estados e caminhos de ícones são reaproveitados quando não mudam.

O relógio só escreve o segundo quando ele muda. O HUD de ISO/exposição conserva a leitura inicial e o intervalo de 500 ms, deixando de publicar pares iguais. Os metadados usados pela automação da câmera continuam sendo atualizados a cada resultado.

## Biblioteca e player

Prévia maior por vídeo, data, tamanho, duração e perfil Log extraído do nome quando disponível. Ações de reprodução, compartilhamento e recuperação continuam no app. O player usa controles nativos persistentes, com barra verde-lima e mensagens de erro no mesmo estilo, inclusive nos backends de recuperação.

Miniaturas têm executor próprio, cache de 6 MiB e no máximo dois trabalhos pendentes. Só a janela visível e sua margem são carregadas; referências de ImageView fora dela são liberadas. A busca de linhas visíveis usa limites ordenados. Retornar à mesma lista conserva os cartões e a posição de rolagem quando o conteúdo não mudou. Decodificar miniaturas não bloqueia a fila de abrir/compartilhar/recuperar vídeos.

## Material gravado e limites da verificação

Na revisão visual 0.7, vinte e um arquivos de pipeline, políticas de captura e assets foram comparados byte por byte com o pacote fonte 0.6. Esse resultado é histórico. A 0.10 altera timestamps/finalização da gravação; não substitui shaders, modelos ou LUTs. As verificações atuais estão em [VALIDACAO.md](../validation/VALIDACAO.md).

Compilação/JVM/lint verificam integração; a referência visual HTML/PNG permite discutir a composição. Ela não renderiza views Android reais. Fluidez, toque, recorte, reprodução e aquecimento ainda devem ser avaliados no A14. Veja [VALIDACAO.md](../validation/VALIDACAO.md).

## Continuidade na 0.8

O visual da câmera permanece. Foco/desfoque ganha estabilidade e bordas; a nova aba Estabilização tem chave e parâmetros separados. O player interno passa a usar viewport retangular e controles persistentes fora da superfície, priorizando confiabilidade de reprodução/áudio. As afirmações de preservação de 21 arquivos acima pertencem à revisão 0.7; na 0.8 o pipeline recebe recursos digitais novos, descritos em [validação](../validation/VALIDACAO.md).


## Monitor e refinamento na 0.9

Monitor Pro reúne histogramas/alertas/contornos e todas as guias; App conserva preferências gerais. Cada ferramenta pode ser ativada sozinha. As chaves e os sliders preservam a interação nativa, dimensões de toque e atualização imediata da captura. O menu informa o domínio analisado e que os indicadores são exclusivos da prévia.

Em paisagem, avisos de IA e estabilização ficam lado a lado, mantendo 48 dp de toque por aviso. Áudio, tempo e histograma compartilham uma linha abaixo do HUD superior, com espaços reservados para manter o timer central e evitar sobreposição. O histograma é compacto com 64 dp de altura. Em retrato, os monitores ocupam os cantos abaixo do timer. Não há animação contínua de medidores; a atualização segue eventos limitados.

O controle de obturador mostra o tempo efetivo escolhido em milissegundos e ângulo, inclusive quando não coincide com a escala discreta de denominadores. Qualidade de compressão e medidor de microfone ficam em Gravação. A versão exibida em App vem dos metadados instalados do pacote, evitando etiquetas de versão antigas. Validação visual nativa e legibilidade com fonte ampliada ainda precisam ser verificadas no aparelho.


## Estabilização — 0.13

A busca inclui giros, rotação, panorâmica e câmera parada. O painel apresenta primeiro a chave e o tipo de movimento, depois compensação de pequenos giros, força/suavidade, margem e análise. Cada configuração tem controle independente. Escolher um tipo não substitui os valores dos sliders. Explicações distinguem correção de giros de nivelamento do horizonte e permitem assistência de fundo com acompanhamento IA sem desfoque.

O tipo, a chave de giros e o recorte ficam bloqueados durante REC; força/suavidade continuam ajustáveis. Estados de compensação, panorâmica, recuperação e margem ajudam a interpretar a referência visual. Nenhuma captura nativa de layout/desempenho foi validada no A14 nesta etapa.
