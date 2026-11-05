---
version: alpha
name: BreakInv Estação
description: Desktop financeiro escuro e denso, com o conteúdo à frente. JavaFX 21 + AtlantaFX PrimerDark, IBM Plex Sans, verde só para ação.
colors:
  primary: "#39CF8B"
  primary-ink: "#06160C"
  bg-0: "#10151D"
  surface: "#18212C"
  surface-2: "#223042"
  text-1: "#EAF0F6"
  text-2: "#A8B6C6"
  focus: "#8ABFFF"
  success: "#74D7A6"
  warning: "#F3C979"
  danger: "#FF9595"
  border: "#2D3A4B"
  border-strong: "#465A70"
  category-fii: "#14b8a6"
typography:
  hero-number:
    fontFamily: IBM Plex Sans
    fontSize: 38px
    fontWeight: 600
  page-title:
    fontFamily: IBM Plex Sans
    fontSize: 26px
    fontWeight: 600
  h1:
    fontFamily: IBM Plex Sans
    fontSize: 20px
    fontWeight: 600
  h2:
    fontFamily: IBM Plex Sans
    fontSize: 18px
    fontWeight: 600
  h3:
    fontFamily: IBM Plex Sans
    fontSize: 15px
    fontWeight: 600
  body-md:
    fontFamily: IBM Plex Sans
    fontSize: 14px
    fontWeight: 400
  body-sm:
    fontFamily: IBM Plex Sans
    fontSize: 13px
    fontWeight: 400
  label-sm:
    fontFamily: IBM Plex Sans
    fontSize: 12px
    fontWeight: 500
  section-title:
    fontFamily: IBM Plex Sans
    fontSize: 13px
    fontWeight: 600
rounded:
  control: 6px
  surface: 10px
spacing:
  field: 8px
  control-content: 12px
  in-section: 16px
  between-sections: 24px
  regions: 32px
components:
  canvas:
    backgroundColor: "{colors.bg-0}"
    textColor: "{colors.text-1}"
  canvas-secondary-text:
    backgroundColor: "{colors.bg-0}"
    textColor: "{colors.text-2}"
  tool-surface:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-1}"
    rounded: "{rounded.surface}"
    padding: 12px
  detail-surface:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.text-1}"
    rounded: "{rounded.surface}"
    padding: 16px
  detail-secondary-text:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.text-2}"
  dialog:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.text-1}"
    rounded: "{rounded.surface}"
    padding: 24px
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.primary-ink}"
    typography: "{typography.label-sm}"
    rounded: "{rounded.control}"
    padding: 8px
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-1}"
    rounded: "{rounded.control}"
    padding: 8px
  text-field:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-1}"
    rounded: "{rounded.control}"
    padding: 8px
  focus-ring:
    backgroundColor: "{colors.bg-0}"
    textColor: "{colors.focus}"
  status-success:
    backgroundColor: "{colors.bg-0}"
    textColor: "{colors.success}"
  status-warning:
    backgroundColor: "{colors.bg-0}"
    textColor: "{colors.warning}"
  status-danger:
    backgroundColor: "{colors.bg-0}"
    textColor: "{colors.danger}"
  divider:
    backgroundColor: "{colors.border}"
  control-frame:
    backgroundColor: "{colors.border-strong}"
  category-chip-fii:
    backgroundColor: "{colors.bg-0}"
    textColor: "{colors.category-fii}"
motion:
  feedback: 140ms
  nav: 200ms
  panel: 220ms
  chart: 320ms
---

# BreakInv: direção visual "Estação"

Fonte: `C:/Projetos/BreakInv-Analises/ciclo-04-experiencia/DIRECAO-C04.md` (sha256 `491f8ad6…a8a1f`, 21/10/2026) e `INVENTARIO-VISUAL.md` (sha256 `a60b551c…07749`). Os valores conferem com `src/main/resources/styles/app.css` em `452215c`. Se este arquivo divergir do DIRECAO-C04, vale o DIRECAO-C04. Se divergir do código, o código é o estado de hoje e este arquivo é o alvo.

Este é um app **desktop JavaFX**, não web. Os tokens em YAML seguem o formato DESIGN.md (Google, `version: alpha`), mas no código eles são **looked-up colors** do `.root` em `app.css` (`-color-bg-0`, `-color-accent` etc.) e propriedades `-fx-*`. Não use flexbox, grid, media query, fonte remota nem CSS web.

## Overview

A identidade é "Estação": canvas escuro, IBM Plex Sans, verde de ação `#39CF8B`, azul de foco `#8ABFFF` e semântica financeira. O C04 mudou a **estrutura** da tela, não o tema. O conteúdo (gráfico, tabela, número) fica à frente; a moldura fica só onde há ferramenta, detalhe ou aviso.

A densidade é a de uma ferramenta desktop para gerir 75 ativos, não a de um dashboard web de cartões. Julgue cada tela também com 75 ativos, não só com um. Não invente métricas para preencher espaço.

Base técnica: JavaFX 21.0.10, AtlantaFX 2.0.1 (PrimerDark como user-agent, `UiTheme.java`), Ikonli Feather 12.3.1 (`FontIcon`), IBM Plex Sans embutida (Regular, Medium e SemiBold, sem rede). Nenhuma biblioteca visual nova entra sem unidade própria.

## Colors

| Token DESIGN.md | Looked-up color no `app.css` | Valor | Papel |
|---|---|---|---|
| `bg-0` | `-color-bg-0` | #10151D | Fundo de trabalho (canvas) |
| `surface` | `-color-surface` | #18212C | Superfície de ferramenta: filtros, formulários, controles |
| `surface-2` | `-color-surface-2` | #223042 | Superfície elevada: detalhe, popover, diálogo |
| `text-1` | `-color-text-1` | #EAF0F6 | Texto primário |
| `text-2` | `-color-text-2` | #A8B6C6 | Texto secundário. `-color-text-3` é alias de `text-2`; não crie um terceiro tom |
| `primary` | `-color-accent` | #39CF8B | Ação (botão primário, estado ativo de ação) |
| `primary-ink` | `-color-accent-ink` | #06160C | Texto sobre o verde de ação |
| `focus` | `-color-info` | #8ABFFF | Foco e seleção |
| `success` / `warning` / `danger` | `-color-success` / `-color-warning` / `-color-danger` | #74D7A6 / #F3C979 / #FF9595 | Semânticos |
| `border` | `-color-border` | #2D3A4B | Separação leve de conteúdo, fio de 1 px |
| `border-strong` | `-color-border-strong` | #465A70 | Moldura de controle editável ou de detalhe |
| `category-*` | `CategoryEnum` | ex.: FII #14b8a6 | Cor de categoria, **sempre com o nome em texto** |

- As variantes com alfa que já existem (`-color-accent-soft`, `-color-danger-a15` etc.) continuam valendo. Não crie hex novo no CSS nem em `setStyle(...)`: use o looked-up color.
- Cor nunca é o único sinal. Categoria leva o nome; status leva texto ou ícone.

## Typography

Uma família só: **IBM Plex Sans**. No JavaFX 21, Medium e SemiBold estáticos aparecem como famílias separadas (`"IBM Plex Sans Medm"` e `"IBM Plex Sans SmBld"`), e um `-fx-font-weight` numérico cai em Regular sem avisar. Por isso o peso vem da família explícita em `styles/typography.css`; ao criar uma classe de texto com peso, inclua-a ali.

| Token | Tamanho | Uso | Classe de hoje |
|---|---|---|---|
| `hero-number` | 38 px (30 px no estreito) | patrimônio no herói | `.hero-number` |
| `page-title` | 26 px | título da página | `.page-title` |
| `h1` / `h2` / `h3` | 20 / 18 / 15 px | títulos de bloco | `.h1` `.h2` `.h3` |
| `body-md` | 14 px | texto base (`.root`) | `.root` |
| `body-sm` | 13 px | texto de apoio, células | — |
| `label-sm` | 12 px | rótulos, legendas, botões compactos | — |
| `section-title` | 13 px SemiBold | cabeçalho de seção | `.section-title` |

Números: saem em pt-BR (`R$ 1.234,56`, `3,22%`, `22.500.000`), nunca pela locale da JVM. Hoje há mistura com en-US (`97.7%`, `R$ 20.00`), registrada como V4a/BRK-12 em `ciclo-03-aparencia/entrega/PENDENCIAS.md`.

## Layout

Espaçamento por relação, sem medida única para tudo:

| Token | Valor | Onde |
|---|---|---|
| `field` | 8 px | entre campos do mesmo formulário |
| `control-content` | 12 px | entre um controle e o seu conteúdo (filtros do gráfico, gráfico e legenda) |
| `in-section` | 16 px | dentro de uma seção |
| `between-sections` | 24 px | entre seções |
| `regions` | 32 px | só entre grandes regiões, no largo |

### Regras de composição (cite pelo número no aceite)

1. **Conteúdo principal sem caixa.** Gráfico e tabela ficam no canvas, sob um cabeçalho de seção (título e uma linha de contexto), separados por espaço e fio. Moldura só para:
   - ferramentas (barra de filtros, formulário);
   - detalhe elevado;
   - avisos.
2. **Uma linha de controle por bloco.** Período e comparação na mesma linha do título do gráfico; busca, categoria, arquivados e contagem numa só barra acima da tabela.
3. **Título, ferramenta e ação dominante identificáveis em 2 s.** Uma ação primária por página (ou por diálogo), no cabeçalho.
4. **Contexto junto do que explica.** A base do valor ("preço de compra como referência") fica no herói. O aviso de token aparece uma vez só, numa linha com ação. As ressalvas financeiras continuam visíveis, mais curtas, sem tooltip obrigatório.
5. **Um só scroll por região.** Nenhum container com scroll concorrente. A tabela da Carteira ocupa a altura disponível no largo.
6. **Larguras pelos conteúdos.** Os mínimos vêm do que cada coluna ou controle mostra e são medidos em runtime nas duas escalas (100% e 150%), sem números mágicos.

### Wireframes de intenção (proporções, não medidas fixas)

Dashboard, largo (≥ 960 px de conteúdo):

```
[ Dashboard · Referência 21/10/2026 · estado BRAPI/sincronização          ação ]
[ ! aviso de token numa linha ......................................... Configurar ]
[ Patrimônio R$ 21.695.487,50  resultado        | Caixa  Posições  Realizado  Não realizado ]   faixa compacta
[ Evolução da carteira   1M 3M 6M 1A … Intervalo  ·  CDI SELIC IPCA IBOV | Composição         ]
[ resumo: variação R$ · % · benchmark                                    | pizza + legenda    ]
[ gráfico (eixo do tempo visível na 1ª dobra)                            | saúde da carteira  ]
[ Atividade recente                                 | Valor por ativo / Variação do dia      ]
```

No estreito, uma coluna na mesma ordem: os filtros refluem e o gráfico mantém altura legível.

Carteira, largo:

```
[ Carteira  Caixa R$ …  Custo R$ …  75 ativos                  Retirada Aporte ··· [+ Novo ativo] ]
[ 🔍 Buscar por nome ou ticker      ✕ | Todas categorias ▾ | ☐ Arquivados        75 de 75 ]
[ tabela de posições (região principal, um scroll)  ‖  detalhe do ativo (superfície elevada) ]
[                                                   ‖  cabeçalho fixo: nome, ticker, valor    ]
[                                                   ‖  campos · ações do ativo                ]
```

- O `‖` é o divisor de um `SplitPane` nativo, ajustável e com pista visual.
- O detalhe tem mínimo pelo conteúdo e máximo útil. A largura fica persistida nas preferências de UI e é preservada ao redimensionar (`SplitPane.setResizableWithParent(detalhe, false)`).
- Teclado: Esc fecha o detalhe; um atalho alterna a largura, porque o divisor do FX 21 só responde a mouse.
- No estreito, o detalhe vira uma seção dedicada com "Voltar à lista" e preserva seleção e filtros.

Viewports de conferência: 1280×800 (largo) e 600×480 (estreito), a 100% e 150%.

## Elevation & Depth

Três planos, pela cor da superfície, não por sombra:

1. `bg-0`: canvas, onde mora o conteúdo principal (regra 1).
2. `surface`: ferramentas (barra de filtros, formulário).
3. `surface-2`: o que fica acima do canvas (detalhe, popover, diálogo).

O fio `border` (1 px) separa conteúdo; o `border-strong` emoldura controle editável e detalhe. Não empilhe superfície dentro de superfície (cartão dentro de cartão).

Dívida conhecida: o `.dark-dialog` de hoje (`app.css`) usa `bg-0`, raio 12 e borda de 1,5 px. A direção pede `surface-2` e raio 10. Ajuste quando a unidade tocar o diálogo, não por varredura.

## Shapes

- Raio `control` = 6 px em controles (botão, campo, pill, segmentado).
- Raio `surface` = 10 px em superfícies (ferramenta, detalhe, diálogo).
- Ícones: só Ikonli Feather (`FontIcon`, prefixo `fth-`), no tamanho do texto que acompanham. Ícone sozinho tem `accessibleText` e tooltip.

## Components

- **Ação primária** (`button-primary`): verde `-color-accent` com texto `-color-accent-ink`. Uma por página ou diálogo (regra 3), e é o botão padrão (Enter) quando houver um. As demais ações são secundárias ou link.
- **Ferramenta** (`tool-surface`): barra única de busca e filtros acima da tabela (regra 2). Busca com `atlantafx.base.controls.CustomTextField`: ícone Feather à esquerda e um `Button` "Limpar busca" à direita, focável, com tooltip e `accessibleText` (não copie o ícone do Sampler, que só aceita mouse).
- **Período e comparação**: segmentado com as pills do AtlantaFX (`Styles.LEFT_PILL`, `CENTER_PILL`, `RIGHT_PILL`), na linha do título do gráfico.
- **Resumo** (herói, faixa, KPIs): componentes próprios `HeroValue`, `StatStrip`, `StatLine` e `KpiCard`, já ligados ao `Motion`.
- **Detalhe** (`detail-surface`): `surface-2`, cabeçalho fixo (nome, ticker, valor), depois campos e ações.
- **Diálogo** (`dialog`): `surface-2`, raio 10, conteúdo agrupado (o quê, quanto, quando), estados normal, erro, ocupado e concluído legíveis. Uma ação primária. Esc e o X equivalem à ação de cancelar ou fechar; o foco volta à origem ao fechar.
- **Aviso**: uma linha com ícone Feather, texto e ação; aparece uma vez (regra 4). Transitório usa o `ToastHost` do app.
- **Tabela densa**: classes `.table-dense` / `.table-analytic` e `Styles.DENSE` do AtlantaFX; cabeçalho alinhado ao texto das células.
- **Opcionais aprovados no inventário**: `Popover` (intervalo De/Até, com `setAnimated(!Motion.reduced())`), `ToggleSwitch` e `Tile` (Configurações; o switch anima 200 ms e precisa zerar com movimento reduzido; o `actionHandler` do Tile só responde a mouse), `InputGroup`.

## Motion

Toda animação parte da classe `presentation.view.util.Motion`: FEEDBACK 140 ms, NAV 200 ms, PANEL 220 ms e CHART 320 ms. O modo "Reduzir animações" desliga tudo, inclusive os gráficos e qualquer componente do AtlantaFX adotado (`Motion.reduced()`). O JavaFX 21 não lê o `reducedMotion` do sistema; a opção é do app.

## Do's and Don'ts

Faça:
- Use os looked-up colors do `app.css` e as classes existentes antes de criar regra nova.
- Cite as regras de composição pelo número (regra 1 a 6) no aceite da unidade.
- Confira na fonte do JavaFX 21.0.10 e do AtlantaFX 2.0.1 toda API ou propriedade CSS antes de usá-la.
- Prove a tela com captura antes e depois, na mesma fixture, a 1280×800 e 600×480, em 100% e 150%.
- Escreva números em pt-BR e a semântica em texto além da cor.

Não use:
- **AtlantaFX `Card`**: repetiria os painéis iguais que o C04 está tirando (regra 1).
- **AtlantaFX `Notification`**: duplica o `ToastHost`.
- **AtlantaFX `DeckPane` e `Animations`**: duplicam o `Motion`.
- ControlsFX e GemsFX: rejeitados no `INVENTARIO-VISUAL.md` (peso, CSS Modena em colisão com o PrimerDark, JavaFX 17 transitivo).
- Hex solto ou `setStyle(...)` inline com cor; scroll dentro de scroll (regra 5); largura fixa sem medida (regra 6); verde de ação para enfeite; um segundo botão primário na mesma página ou diálogo.
