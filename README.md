# 📈 BreakInv

Aplicação desktop desenvolvida em **Java + JavaFX** para **controle, análise e acompanhamento de investimentos**, com persistência local em **SQLite** e integração com serviços externos para complementar **cotações, benchmarks, indicadores e histórico de ativos**.

---

## 📌 Visão Geral

O **BreakInv** é um sistema desktop voltado para a **organização da carteira de investimentos**, permitindo registrar ativos, acompanhar movimentações, visualizar a evolução patrimonial e analisar a performance do portfólio em uma interface local.

A proposta do projeto é funcionar de forma **local-first**, com banco embarcado em **SQLite**, sem depender de um backend dedicado para o fluxo principal da aplicação. As integrações externas entram como complemento analítico, enriquecendo a experiência com dados de mercado e comparativos.

---

## ✨ Principais recursos

- Dashboard com visão consolidada da carteira
- KPIs de patrimônio, lucro/prejuízo e acompanhamento patrimonial
- Snapshot diário para evolução da carteira ao longo do tempo
- Extrato de movimentações com visão consolidada por período
- Análise de diversificação do portfólio
- Simulação de cenários de investimento
- Ranking de ativos com destaques de desempenho
- Página de **Análise de Ativos** com consulta de ticker, indicadores e histórico
- Configuração de token para integrações externas
- Persistência local automática com **SQLite**
- Interface desktop com navegação por páginas e foco em visualização de dados

---

## 🧱 Tecnologias utilizadas

- **Java 21**
- **JavaFX**
- **Maven**
- **SQLite**
- **SQLite JDBC**
- **OkHttp**
- **Gson**
- **Ikonli**
- **CSS**
- **JUnit 5**

---

## 🏛️ Estrutura do projeto

```text
BreakInv/
├── src/
│   ├── main/
│   │   ├── java/com/daniel/
│   │   │   ├── core/                    # Domínio, contratos e regras de negócio
│   │   │   ├── infrastructure/          # APIs, persistência e configurações
│   │   │   ├── main/                    # Inicialização da aplicação
│   │   │   └── presentation/            # Telas, componentes e navegação
│   │   └── resources/
│   │       └── styles/                  # Estilos visuais da aplicação
│   └── test/
│       └── java/com/daniel/             # Testes automatizados
├── pom.xml
└── README.md
````

---

## 🖥️ Páginas da aplicação

| Página              | Descrição                                                      |
| ------------------- | -------------------------------------------------------------- |
| `Dashboard`         | Visão geral da carteira com KPIs, gráficos e comparativos      |
| `Asset Analysis`    | Consulta de ticker, métricas de mercado e histórico de preços  |
| `Ranking`           | Destaques de ativos com base em desempenho                     |
| `Extrato / Reports` | Histórico e consolidação de movimentações e resultados         |
| `Diversificação`    | Distribuição patrimonial e leitura da concentração da carteira |
| `Simulação`         | Projeção de cenários para apoio à tomada de decisão            |
| `Investment Types`  | Organização dos tipos/categorias de investimento               |
| `Configurações`     | Ajustes da aplicação e configuração de integrações             |

---

## 🌐 Integrações externas

O BreakInv utiliza integrações externas para enriquecer a análise local da carteira.

### BRAPI

Utilizada para apoiar a consulta de:

* cotações de ativos
* dados de mercado
* histórico de preços
* comparativos e informações usadas em análises da aplicação

### Banco Central do Brasil (BCB)

Utilizado para consulta de indicadores econômicos e benchmarks que ajudam na leitura de desempenho e comparação da carteira.

> O funcionamento principal da aplicação continua centrado na persistência local. As integrações externas complementam a análise, mas não substituem a base local do sistema.

---

## 💾 Persistência de dados

A aplicação utiliza **SQLite** como banco local e cria automaticamente a base de dados ao iniciar o sistema.

Arquivo gerado localmente:

```text
breakinv.db
```

Entre os dados persistidos pela aplicação, estão:

* investimentos cadastrados
* movimentações
* snapshots patrimoniais
* configurações da aplicação

Isso torna o projeto adequado para uso local, estudo de arquitetura desktop e construção de portfólio técnico com persistência embarcada.

---

## ⚙️ Como executar o projeto

### Pré-requisitos

Antes de iniciar, tenha instalado:

* **JDK 21**
* **Maven**

### Executar em ambiente de desenvolvimento

```bash
mvn clean javafx:run
```

A aplicação será iniciada localmente com interface desktop JavaFX.

### Rodar os testes

```bash
mvn test
```

### Gerar build do projeto

```bash
mvn clean package
```

Os artefatos gerados ficarão em:

```text
target/
```

---

## 🧭 Funcionamento da aplicação

O BreakInv segue uma proposta de aplicação desktop com foco em uso local:

* a interface é construída com **JavaFX**
* os dados da carteira ficam persistidos em **SQLite**
* a aplicação sobe localmente e prepara o banco automaticamente
* o usuário navega pelas páginas dentro de uma shell principal com sidebar
* os dados externos são consumidos apenas para enriquecer indicadores, comparativos e análises

---

## 📊 O que a aplicação permite analisar

Com o BreakInv, o usuário pode acompanhar:

* evolução do patrimônio
* lucro ou prejuízo consolidado
* movimentações da carteira
* distribuição entre categorias
* concentração patrimonial
* comparação com benchmarks
* comportamento histórico de ativos consultados
* apoio visual para entender performance e composição do portfólio

---

## 🧠 Objetivo do projeto

Este projeto foi desenvolvido com foco em:

* prática de desenvolvimento desktop com **JavaFX**
* organização em camadas
* persistência local com **SQLite**
* consumo de APIs com **OkHttp** e **Gson**
* modelagem de regras de negócio no contexto de investimentos
* construção de interface rica em dados, gráficos e indicadores
* composição de portfólio com uma aplicação Java completa

---

## 📌 Status do projeto

O **BreakInv** está em evolução contínua, com foco em:

* refinamento visual
* melhoria da experiência de uso
* evolução das análises da carteira
* expansão das integrações e leituras de mercado
* amadurecimento da arquitetura desktop do projeto

---

## 📄 Licença

Este projeto está licenciado sob a **MIT License**.
Consulte o arquivo `LICENSE` para mais detalhes.
