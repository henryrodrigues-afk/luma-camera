# Referências visuais

O conceito HTML da versão 0.7 está preservado em [archive/0.7/luma-0.7.html](archive/0.7/luma-0.7.html). Ele documenta uma referência histórica; a interface Android atual está implementada em [MainActivity.kt](../app/src/main/java/com/lumacamera/MainActivity.kt) e nas classes de `app/src/main/java/com/lumacamera/ui/`.

A imagem renderizada desse conceito fica em [dist/resources/previews/luma-0.7-board.png](../dist/resources/previews/luma-0.7-board.png). Para renderizar novamente, execute na raiz:

```powershell
node scripts/tools/RenderDesign.cjs
```

Veja as decisões de interface em [docs/development/DESIGN.md](../docs/development/DESIGN.md) e os requisitos do renderizador no [guia de scripts](../scripts/README.md).
