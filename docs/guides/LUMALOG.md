# LumaLog v2 — imagem flat e dessaturada para edição

O LumaLog v2 aplica uma curva logarítmica ao vídeo SDR que o Android entrega e comprime a crominância de forma reversível. A imagem gravada fica com pretos elevados, realces comprimidos e cores menos intensas, pronta para a LUT inversa e para a correção no editor. Ele funciona por processamento local, sem depender da opção de Log nativo da câmera.

A versão anterior, v1, remapeava os canais com uma curva Log, mas não tinha uma etapa específica para reduzir a crominância. O v2 corrige essa diferença de aparência: a 100%, retém 60% da crominância do sinal depois da curva, preservando sua luminância ponderada. Isso não equivale a apagar as cores: a compressão tem inversa definida, incluída na assistência e na nova LUT.

## Como usar

1. Abra **Imagem** e use **Preparar imagem para edição**. O botão ativa LumaLog v2 a 100%, deixa a assistência desligada e desativa os ajustes criativos de cor, Flat, ganho, nitidez e rastro. Os ajustes da câmera e os desfoques permanecem disponíveis.
2. Com a assistência desligada, a tela mostra o perfil flat e dessaturado que será gravado. Ative **Monitorar em SDR · só na tela** quando quiser ver uma imagem com contraste e cores restaurados. Essa assistência afeta somente a tela; o MP4 continua com LumaLog. O indicador **TELA SDR** identifica essa visualização.
3. Evite estourar as áreas claras durante a captura. Se houver compensação de exposição disponível, reduza-a um pouco quando necessário.
4. Exporte a LUT `.cube` da versão e da força utilizadas. O app usa 33 pontos a 100% e 65 em forças parciais. A LUT pré-gerada `app/src/main/assets/luts/LumaLog-v2-to-SDR-33.cube` corresponde ao v2 a 100%. A versão e a força também aparecem no nome do novo vídeo, por exemplo `_LOGv2_100pct`.
5. No editor, interprete o arquivo como vídeo SDR comum e aplique a LUT como primeiro passo da correção. Depois ajuste exposição, contraste e cores.

Não atribua Apple Log, S-Log, LogC ou Log3G10 ao MP4: cada um usa uma curva e um espaço de cor específicos, que não correspondem ao LumaLog. Esta LUT restaura a curva do sinal SDR de entrada; ela não faz uma conversão de gama de cores. O espaço de cor efetivo e o processamento prévio do fabricante podem variar entre aparelhos. A hipótese de uma transferência semelhante a sRGB permite definir uma curva consistente e sua inversa, mas não é uma calibração colorimétrica do sensor do A14.

## Curva e compressão de cores reproduzíveis

Para cada canal `c`, primeiro limitamos a faixa a `[0,1]` e usamos a inversa sRGB como referência:

```
linear(c) = c / 12.92                         se c <= 0.04045
            ((c + 0.055) / 1.055) ^ 2.4       caso contrário

LumaLog(c) = 0.09375 + 0.84375 * ln(1 + 63 * linear(c)) / ln(64)
canal(c)  = (1 - força) * c + força * LumaLog(c)

Y         = 0.2126 * canal(R) + 0.7152 * canal(G) + 0.0722 * canal(B)
retenção  = 1 - 0.4 * força
saída(RGB)= Y + (canal(RGB) - Y) * retenção
```

A 100%, o preto neutro fica em `0.09375` e o branco neutro em `0.9375`. A força de 0% mantém a imagem original. A compressão de crominância é progressiva: a 50%, a retenção é 80%; a 100%, é 60%. Como os pesos de luminância somam 1, essa etapa preserva `Y` e as cores neutras. Para desfazê-la, calculamos `Y` do RGB gravado e usamos `canal(RGB) = Y + (saída(RGB) - Y) / retenção`; depois invertemos a curva de cada canal.

A assistência usa a inversa direta da curva a 100% e uma busca binária com nove iterações para forças intermediárias. A LUT exportada usa maior precisão, com uma tabela interna de 16.385 amostras para acelerar a inversa escalar. O v2 exige uma **LUT 3D real**: desfazer a crominância de um canal depende também dos outros dois canais.

A LUT v2 contém valores de saída menores que zero e maiores que um em pontos que ficam fora do conjunto de cores que o perfil gera. Isso permite uma interpolação estável junto às bordas desse conjunto. Use um editor que preserve valores de ponto flutuante na LUT e limite a saída somente após a conversão; recortar cada ponto da tabela antes da interpolação pode alterar cores muito saturadas.

Os ajustes criativos ficam em **Imagem → Ajustes de cor e efeitos** e são aplicados antes do Log. Todos mantêm seus interruptores individuais. **Preparar imagem para edição** faz uma limpeza explícita uma única vez; ajustes reativados depois continuam funcionando. Para um ponto de partida previsível, mantenha Flat e os ajustes criativos desligados. A LUT inversa desfaz somente LumaLog, mantendo qualquer outra alteração que já esteja gravada. O rastro opcional é composto depois da curva, no domínio gravado; sua aparência restaurada pode diferir de uma média em luz linear.

## Compatibilidade com vídeos anteriores

O menu oferece **v1 legado** e **v2 para edição**. Clipes antigos da versão 0.4 usam v1; aplique `LumaLog-v1-to-SDR-33.cube` quando gravados a 100%. Esse arquivo é preservado sem alterações. Para uma força parcial de um clipe antigo, selecione v1 e a força correspondente antes de exportar a LUT. A LUT v2 não deve ser aplicada a clipes v1, nem a v1 a clipes v2.

## Limites concretos

- O sinal já passou pelo processamento e pela exposição da câmera. Log simulado não recupera realces que já foram recortados nem cria detalhes ausentes no sensor.
- A gravação atual é H.264 SDR de 8 bits. A compressão e a quantização podem produzir faixas em gradientes; a curva não transforma o arquivo em RAW, HDR ou 10 bits.
- A LUT deve corresponder à versão e à força usadas no clipe. O app mantém a configuração do perfil estável durante a gravação; a assistência de tela não modifica o perfil do arquivo.
- A dessaturação reversível concentra a informação de cor em uma faixa menor. Em 8 bits, isso aumenta a sensibilidade da restauração à quantização e à compressão. Priorize luz adequada e a maior taxa de bits estável disponível; um visual lavado, sozinho, não garante mais margem de edição.
- A LUT de 33 pontos serve como conversão de referência e tem erro de interpolação. Em 729 amostras sintéticas de RGB, já quantizadas a 8 bits, a inversa v2 a 100% ficou dentro de aproximadamente 2 níveis de 255 por canal. Esse teste não mede ruído do sensor, compressão H.264 ou qualidade no aparelho. A aparência final ainda depende da luz, da exposição e do processamento do fabricante.

## Verificação e referências

Comparação visual sintética reproduzível: [log-v2-comparacao.png](../../dist/resources/previews/log-v2-comparacao.png), gerada por `scripts/tools/RenderLogComparison.cjs` com cores quantizadas e a LUT v2 real. Mostra SDR, perfil a 100% e retorno pela LUT. Não é captura do A14 nem medição de ruído ou compressão H.264.

`SimulatedLogTest` verifica a curva legado, a redução de crominância v2, preservação da luminância, inversas de cores em forças completas/parciais, quantização de 8 bits e interpolação trilinear da LUT 3D exportada. `scripts/VerifyShaders.cjs` compara os shaders reais com a referência, confirma a restauração da prévia e mantém a cópia destinada ao encoder sem assistência. Também verifica os quatro cantos da imagem nas quatro rotações, com e sem espelhamento.

O [manual oficial do Open Camera](https://opencamera.org.uk/help.html) descreve seus perfis Log como imagens flat destinadas à pós-produção e recomenda considerar uma taxa de bits maior. O [histórico oficial](https://opencamera.org.uk/history.html) registra os perfis de gama e JTLog/JTLog2. Eles são referências funcionais; nenhum código GPL ou curva desses projetos foi copiado para o LumaLog.

O conceito de separar a codificação Log e a aparência de exibição é explicado pela [RED em REDlogFilm e REDgamma](https://www.red.com/red-101/redlogfilm-redgamma). O uso de LUTs `.cube` aparece no [SDK Blackmagic RAW](https://documents.blackmagicdesign.com/DeveloperManuals/BlackmagicRAW-SDK.pdf). O LumaLog usa a fórmula própria acima e não implementa os perfis dessas câmeras.
