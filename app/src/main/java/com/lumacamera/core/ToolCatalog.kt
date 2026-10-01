package com.lumacamera.core

data class CameraTool(val key: String, val title: String, val page: String, val description: String, val keywords: String)

/** Single navigation source: every indexed tool opens the existing control, never a second copy. */
object ToolCatalog {
    val tools = listOf(
        CameraTool("presets", "Presets personalizados", "PRESETS", "Salve e aplique combinações de ajustes", "perfil configuração salvar favoritos"),
        CameraTool("video", "Formato de gravação", "VIDEO", "Câmera, resolução, FPS e compressão", "4k 1080 bitrate qualidade megapixel"),
        CameraTool("parts", "Gravações longas em partes", "VIDEO", "Divida uma sessão em arquivos menores", "segmento contínua longa limite armazenamento"),
        CameraTool("audio", "Microfone e áudio", "AUDIO", "Escolha a entrada e confira a rota real", "usb externo lapela som medidor pico"),
        CameraTool("log", "Log para edição", "LOOK", "Perfil gravado e LUT de conversão", "dessaturado cor curva editar flat"),
        CameraTool("lut", "LUT na prévia", "LOOK", "Experimente cores somente na tela", "cube look importação monitor sdr"),
        CameraTool("color", "Ajustes de cor e efeitos", "LOOK", "Ganho, temperatura, contraste e textura", "saturação nitidez rastro oval brilho"),
        CameraTool("scopes", "Waveform, RGB e vectorscope", "MONITOR", "Leia exposição e cor do sinal gravado", "monitores histograma parade escopos"),
        CameraTool("guides", "Guias e composição", "MONITOR", "Grade, proporção, área segura e nível", "horizonte terços enquadramento"),
        CameraTool("exposure", "Exposição e balanço de branco", "SENSOR", "ISO, obturador, EV, AE e AWB", "sensor manual automático ângulo"),
        CameraTool("focus", "Foco e trava da lente", "FOCUS", "Foque por toque, manualmente ou trave", "af distância lock infinito perto"),
        CameraTool("tracking", "Acompanhamento por IA", "FOCUS", "Siga a pessoa ou o objeto selecionado", "rastrear tracking seguir alvo pessoas objetos"),
        CameraTool("rack", "Foco A/B", "FOCUS", "Movimente a lente entre duas distâncias", "rack focus transição pontos"),
        CameraTool("blur", "Desfoque e Cinema", "FOCUS", "Recorte do assunto e transição digital", "retrato bokeh máscara bordas estabilidade"),
        CameraTool("zoom", "Zoom suave A/B", "ZOOM", "Programe início, fim e duração", "aproximação movimento velocidade recorte"),
        CameraTool("stabilization", "Estabilização", "STABILIZATION", "Movimento, pequenos giros e margem de correção", "tremor digital estabilidade rotação giro panorâmica câmera parada força suavidade recorte"),
        CameraTool("projects", "Projetos, cenas e tomadas", "PROJECT", "Identifique seus clipes para editar", "takes produção nome ficha metadados"),
        CameraTool("conditions", "Condições para gravar", "VIDEO", "Consulte espaço, bateria e alerta térmico", "temperatura armazenamento tempo disponível"),
        CameraTool("app", "Preferências e diagnóstico", "APP", "Permissões, botões e recursos do aparelho", "restaurar ajuda volume tela"),
    )
    fun search(query: String): List<CameraTool> = tools.filter { WorkspacePolicy.matches(query, it.title, "${it.description} ${it.keywords}") }
    fun favorites(keys: List<String>) = keys.distinct().take(6).mapNotNull { key -> tools.firstOrNull { it.key == key } }
}
