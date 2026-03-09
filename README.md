# 📈 BreakInv

Aplicação desktop desenvolvida em **Java + JavaFX** para **controle e análise de investimentos**, com persistência local em **SQLite** e integração com serviços externos para complementar informações de mercado, indicadores e comparativos de performance.

---

## 📌 Visão Geral

O **BreakInv** é um sistema desktop voltado para **organização da carteira de investimentos**, **acompanhamento do patrimônio** e **visualização da performance do portfólio**.

O projeto foi estruturado para funcionar de forma **local**, com banco embutido em **SQLite**, permitindo que os dados fiquem persistidos no próprio ambiente da aplicação, sem depender de um backend separado para o funcionamento principal.

Além disso, a aplicação pode consumir informações externas para enriquecer a análise da carteira, como benchmarks de mercado e cotações utilizadas em comparativos de desempenho.

---

## 🧱 Tecnologias Utilizadas

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

## 🏛️ Estrutura do Projeto

```text
BreakInv/
├── src/
│   ├── main/
│   │   ├── java/com/daniel/
│   │   │   ├── core/                    # Domínio, contratos e regras de negócio
│   │   │   ├── infrastructure/          # Configuração, APIs e persistência
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

## 🚀 Funcionalidades

* Dashboard com visão consolidada da carteira
* Exibição de **patrimônio total**
* Cálculo de **lucro / prejuízo**
* Comparação da carteira com benchmarks
* Indicadores de apoio para análise do portfólio
* Visualização de **diversificação por categoria**
* Distribuição patrimonial em gráficos
* Ranking de ativos com **maiores altas e baixas**
* Simulação de cenários de investimento
* Extrato de investimentos e movimentações
* Cadastro e gerenciamento dos ativos da carteira
* Configuração de token para integração externa
* Persistência local automática com **SQLite**
* Snapshot diário para acompanhamento da evolução patrimonial

---

## 🖥️ Páginas da Aplicação

| Página                     | Descrição                                                  |
| -------------------------- | ---------------------------------------------------------- |
| `Dashboard`                | Resumo geral da carteira com KPIs, gráficos e comparativos |
| `Cadastrar Investimento`   | Cadastro e gerenciamento dos investimentos                 |
| `Diversificação`           | Análise da distribuição da carteira                        |
| `Simulação`                | Projeção de cenários e apoio à tomada de decisão           |
| `Extrato de Investimentos` | Histórico e visão consolidada das movimentações            |
| `Configurações`            | Ajustes da aplicação e configuração de integrações         |

---

## 🌐 Integrações Externas

O projeto utiliza integrações externas para complementar a análise local da carteira.

### 1. BRAPI

Utilizada para apoiar a consulta de dados de mercado, cotações e comparativos de performance no dashboard.

### 2. Banco Central do Brasil (BCB)

Utilizado para consulta de indicadores e benchmarks econômicos, como apoio aos comparativos exibidos na aplicação.

> **Observação:** o funcionamento principal do sistema continua centrado na persistência local. As integrações externas enriquecem a análise, mas a base da aplicação permanece local.

---

## 💾 Persistência de Dados

A aplicação utiliza **SQLite** como banco local e cria automaticamente a base de dados ao iniciar o sistema.

O arquivo gerado localmente é:

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

## ⚙️ Como Executar o Projeto

### Pré-requisitos

Antes de iniciar, tenha instalado:

* **JDK 21**
* **Maven**

### Ambiente de desenvolvimento

```bash
mvn clean javafx:run
```

A aplicação será iniciada em ambiente local com interface desktop JavaFX.

---

## 🏗️ Build do Projeto

Para compilar e empacotar o projeto:

```bash
mvn clean package
```

Os artefatos gerados ficarão na pasta:

```text
target/
```

---

## 🧭 Funcionamento da Aplicação

O **BreakInv** segue uma proposta de aplicação desktop com foco em uso local:

* a interface é construída com **JavaFX**
* os dados da carteira ficam persistidos em **SQLite**
* a aplicação sobe diretamente pela classe principal do projeto
* o banco é preparado automaticamente na inicialização
* o usuário navega pelas páginas do sistema dentro de uma shell principal com sidebar

---

## 📊 Análise de Carteira

A proposta do projeto é oferecer uma visão prática da carteira do usuário, permitindo acompanhar:

* evolução do patrimônio
* lucro ou prejuízo consolidado
* distribuição entre categorias
* comparação com benchmarks
* apoio visual para entender concentração e performance

Com isso, o sistema funciona tanto como ferramenta de estudo quanto como projeto de portfólio com foco em domínio financeiro e aplicação desktop.

---

## 🧠 Objetivo do Projeto

Este projeto foi desenvolvido com foco em:

* prática de desenvolvimento desktop com **JavaFX**
* organização em camadas
* persistência local com **SQLite**
* consumo de APIs com **OkHttp** e **Gson**
* construção de interface rica em dados e gráficos
* modelagem de regras de negócio para acompanhamento patrimonial
* composição de portfólio com uma aplicação Java completa

---

## 📌 Status do Projeto

O **BreakInv** está em evolução contínua, com foco em refinamento visual, melhoria das análises e expansão dos recursos relacionados ao acompanhamento da carteira.

---

## 📄 Licença

Este projeto está licenciado sob a **MIT License**.
Consulte o arquivo `LICENSE` para mais detalhes.
