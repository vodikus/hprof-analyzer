# hprof-analyzer — Guia do usuário

[English](../en/guide.md) · [Voltar ao README](../../README.md)

- [1. Visão geral](#1-visão-geral)
- [2. Build e instalação](#2-build-e-instalação)
- [3. Gerando um heap dump](#3-gerando-um-heap-dump)
- [4. Executando o analisador](#4-executando-o-analisador)
- [5. Lendo o relatório](#5-lendo-o-relatório)
- [6. Conceitos](#6-conceitos)
- [7. Desempenho e memória](#7-desempenho-e-memória)
- [8. Idiomas (i18n)](#8-idiomas-i18n)
- [9. Arquitetura](#9-arquitetura)
- [10. Limitações](#10-limitações)
- [11. Solução de problemas](#11-solução-de-problemas)

## 1. Visão geral

O `hprof-analyzer` lê um heap dump da JVM (`.hprof`) e gera dois relatórios:

- **HTML**: um único arquivo autocontido. A biblioteca de gráficos (Apache ECharts) e todos os dados ficam embutidos,
  então ele abre offline e pode ser anexado a um chamado ou enviado por e-mail. Segue o tema claro/escuro do sistema.
- **Markdown**: o mesmo conteúdo em tabelas, com os caminhos até GC roots desenhados como diagramas Mermaid
  (renderizados pelo GitHub, GitLab e pela maioria dos visualizadores de Markdown).

A leitura do heap é feita pelo [Shark](https://square.github.io/leakcanary/shark/), a biblioteca de análise de heap
do LeakCanary. A ferramenta é voltada a dumps do HotSpot/OpenJDK.

## 2. Build e instalação

Requisitos: JDK 21 ou mais novo. Não é preciso instalar o Gradle; o wrapper baixa sozinho.

```bash
./gradlew shadowJar   # build/libs/hprof-analyzer-all.jar (jar executável único, com todas as dependências)
./gradlew test        # testes unitários e ponta a ponta
```

O teste ponta a ponta gera um heap dump da própria JVM, analisa e confere os relatórios. Ele também grava relatórios
de exemplo em `build/test-report.md`, `build/test-report.html` e `build/test-report-en.html`.

O `hprof-analyzer-all.jar` pode ser copiado para qualquer lugar; não tem outra dependência em tempo de execução.

## 3. Gerando um heap dump

| Método | Comando |
| --- | --- |
| Processo em execução | `jcmd <pid> GC.heap_dump /caminho/app.hprof` |
| Processo em execução (jmap) | `jmap -dump:live,format=b,file=/caminho/app.hprof <pid>` |
| Em `OutOfMemoryError` | iniciar a JVM com `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/caminho/` |
| Pelo código | `ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java).dumpHeap(caminho, true)` |

`jcmd GC.heap_dump` e `jmap -dump:live` rodam um GC completo antes, então o dump só tem objetos vivos. Use
`jcmd <pid> GC.heap_dump -all` para manter também os objetos inalcançáveis.

Heap dumps contêm tudo o que a aplicação tinha em memória (senhas, tokens, dados pessoais). Trate os dumps e os
relatórios gerados como arquivos sensíveis.

## 4. Executando o analisador

```bash
java -Xmx4g -jar hprof-analyzer-all.jar <dump.hprof> [opções]
```

| Opção | Padrão | Descrição |
| --- | --- | --- |
| `--out <dir>` | `.` | Diretório de saída, criado se não existir. Os arquivos recebem o nome do dump (`app.hprof` → `app.html`, `app.md`). |
| `--format html,md` | `html,md` | Lista de formatos separados por vírgula. `json` grava `<dump>.snapshot.json` com o histograma completo (base para comparar dumps). |
| `--top <n>` | `50` | Linhas em cada tabela (classes, objetos, strings, arrays). |
| `--no-retained` | desligado | Pula a dominator tree. Cerca de 2× mais rápido e usa menos memória, mas ficam vazios os retained sizes, o treemap e a seção de maiores objetos, e os caminhos até GC root só são calculados para `--leak-class`. |
| `--threshold k=v,...` | — | Limiares do painel de saúde (frações: `0.3` = 30%). Chaves: `bigDominator` (0.30), `lowFill` (0.25), `minFillInstances` (1000), `finalizerQueue` (10000), `poolQueue` (10000), `objectMappers` (10), `heapGrowth` (0.20), `webappLoaders` (2), `dupStrings` (0.10), `emptyCollections` (0.05), `poolsPerClass` (10), `offHeapRatio` (1.0), `duplicateClasses` (1), `softRefs` (0.10), `openFiles` (500), `sockets` (1000), `bigStaticCollection` (100000), `unreachable` (0.10), `libraryVersions` (1). Chave desconhecida encerra com erro. |
| `--no-redact` | desligado | Por padrão o relatório mascara `user.name`, `user.home`, `user.dir`, class/module/library path, os argumentos de `sun.java.command` (fica só o 1º token) e propriedades ou `-Dchave=valor` cuja chave contém `pass`, `secret`, `token`, `credential`, `auth` ou `key`. Além disso, o diretório do usuário (em todas as grafias) vira `~` e o nome do usuário vira `‹user›` em qualquer texto do relatório e do snapshot (caminhos em `java.home`, nomes de threads, campos de objetos). Esta opção mostra tudo. |
| `--leak-class a.B,c.D` | — | Nomes completos de classes. Até 20 instâncias dessas classes ganham caminho até GC root, além dos 10 maiores objetos. |
| `--app-package a.b,c.d` | automático | Pacotes da aplicação para o relatório extra `<dump>-app.html`/`.md` (só classes da aplicação). Sem a opção, usa o pacote da main class (`sun.java.command`) ou, se for um jar/launcher, tudo fora de JDK, linguagem e frameworks/bibliotecas conhecidos. |
| `--baseline a.json,b.json` | — | Snapshots de dumps anteriores (gerados com `--format json`). O relatório ganha a seção de comparação entre dumps. |
| `--i18n <código>` | `pt-BR` | Idioma dos relatórios e das mensagens do console. Veja a [seção 8](#8-idiomas-i18n). |
| `-h`, `--help` | — | Mostra a versão e o uso (no idioma de `--i18n`). |
| `-V`, `--version` | — | Mostra a versão (`hprof-analyzer 1.0.0`) e sai. |

A primeira linha no stderr é o nome e a versão da ferramenta, seguida do progresso; os caminhos dos arquivos gerados
saem no stdout. A versão também aparece no rodapé dos relatórios ("Gerado por hprof-analyzer 1.0.0") e no menu
lateral do HTML. Argumentos inválidos encerram com
código 2.

Exemplos:

```bash
# Relatório em inglês, só HTML
java -Xmx4g -jar hprof-analyzer-all.jar app.hprof --out relatorio --format html --i18n en

# Olhada rápida em um dump muito grande
java -Xmx8g -jar hprof-analyzer-all.jar enorme.hprof --no-retained --top 30

# Descobrir por que instâncias de uma classe suspeita continuam em memória
java -Xmx4g -jar hprof-analyzer-all.jar app.hprof --leak-class com.acme.SessionContext,com.acme.CacheEntry
```

## 5. Lendo o relatório

As seções ficam em seis camadas, da conclusão para a evidência (mesma ordem no HTML e no Markdown):

| Camada | Seções |
| --- | --- |
| Diagnóstico | Resumo executivo, Painel de saúde, Avisos, Resumo (com contexto do dump e dimensionamento), Por onde começar, Comparação entre dumps |
| Onde está a memória | Orçamento de memória, Dominator tree, Retained agregado, Maiores objetos, Histograma, Pacotes, Off-heap |
| Por que está retida | Suspeitas de leak, Caminhos agregados, Caminhos até GC roots, Referências |
| Desperdício | Desperdício de memória (com boxing), Strings duplicadas, Maiores arrays |
| Runtime | Threads (com stack traces), Concorrência, Frameworks |
| Apêndice técnico | Ambiente JVM, Classes geradas, ClassLoaders, GC roots, Bibliotecas, Grafo, Metadados |

Análises automáticas (só no relatório completo):
- **Resumo executivo**: 3 a 7 frases geradas por regras: heap alcançável, off-heap e lixo no dump; os maiores
  componentes do orçamento; se o dump veio de um OOM; sinais de vazamento; por onde começar; achados do painel e a
  faixa de `-Xmx` sugerida.
- **Orçamento de memória**: atribuição exclusiva sobre a dominator tree. Cada objeto pertence ao componente
  (aplicação, cada framework/biblioteca, JDK, gerado em runtime) do dominador não-JDK mais próximo, senão ao da sua
  classe. As partes somam exatamente o heap alcançável; top 7 + "Outros", com o off-heap na mesma escala.
- **Por onde começar**: nota 0–100 para as 30 maiores classes da aplicação/bibliotecas (classes do JDK e arrays ficam
  de fora: são o conteúdo, não a causa). Fatores: fatia do heap retida (cheia a partir de 30%, peso 35%), crescimento
  entre dumps com `--baseline` (25%), retida por campo estático (15%) ou por coleção (15%) e profundidade média do
  caminho (10%). Achados benignos valem 30%.
- **Contexto do dump**: pós-OOM quando há `OutOfMemoryError` em variável local de thread ou um OOM não pré-alocado
  retido; `-XX:+HeapDumpOnOutOfMemoryError` aparece só como indício. "Muito lixo" quando a regra `unreachable` dispara.
- **Dimensionamento** (heurística): `-Xmx` entre 3× e 4× o live set e `MaxDirectMemorySize` ≥ 1,5× os buffers diretos,
  comparado com o `-Xmx` atual quando os argumentos estão no heap.
- **Desperdício por campo dono**: coleções com capacidade ociosa e strings duplicadas agrupadas pelo campo que as segura
  (`Foo.cache`), subindo pelos internos de coleções do JDK. Nas strings, cada cópia contribui com sua parte do
  desperdício (`bytes × (cópias − 1) / cópias`), então os donos somam o desperdício total. Donos internos do JDK (ex.:
  `AppClassLoader.parallelLockMap`) ficam ocultos por padrão: no HTML há uma caixa para mostrá-los; no Markdown, uma
  linha diz quantos foram omitidos. Classes definidas por mais de um ClassLoader são info com isolamento de plugins e
  alerta quando alguma biblioteca aparece em versões diferentes.
- **Bibliotecas**: JARs dos `ZipFile` abertos e do class path (só o nome do arquivo), com versão lida do nome; mesma
  biblioteca em versões diferentes vira alerta.
- **Regras novas no painel**: classes definidas por mais de um ClassLoader, bytes mantidos só por `SoftReference`,
  coleção grande em campo estático, muitos arquivos/sockets abertos, dump sem `:live`, bibliotecas em mais de uma
  versão e dump pós-OOM.

No HTML:
- **Menu lateral** agrupado por camada, com **busca global**: o termo filtra todas as tabelas e esmaece as seções sem
  resultado.
- **Tabelas** ordenáveis pelo cabeçalho, com filtro próprio e botão **CSV** (exporta as linhas visíveis).
- **IDs de objeto clicáveis** (`0x…`): abrem um painel com classe, shallow, retained, dominador imediato, campos e o
  caminho até o GC root. Os ids dentro do painel também são clicáveis.
- **Âncoras persistentes**: `#leaks/2`, `#paths/3`, `#merged/1`, `#retained/2` (seletores) e `#obj/0x…` (painel)
  reabrem o mesmo ponto; o endereço muda ao trocar o seletor, pronto para colar num ticket.
- **Cor por origem** nos gráficos de classes, pacotes, campos estáticos, dominadores e pools: aplicação, framework/
  biblioteca, JDK e gerado em runtime, sempre com as mesmas cores e legenda.
- **Achados benignos** (`ZipFile$Source` do classpath, caches de `Locale`/`MethodType`, threads internas da JVM)
  aparecem esmaecidos, no fim da tabela, com o motivo no tooltip. No Markdown, recebem o sufixo "(benigno: motivo)".
- **Renderização sob demanda**: cada gráfico é desenhado quando chega perto da tela; stack traces são montados ao
  abrir.

### Resumo
Versão do hprof, tamanho de identificador (4 ou 8 bytes), data/hora do dump (UTC), contagem de objetos, classes,
instâncias, object arrays e primitive arrays, número de GC roots, shallow total e quantos objetos e bytes são
alcançáveis por referências fortes.

### Classes geradas / proxies (metaspace)

Classes criadas em runtime (ByteBuddy, Hibernate, CGLIB/Spring, Mockito, Javassist, JDK Proxy), agrupadas por gerador e classe base, com o número de ClassLoaders que as definiram. Um grupo é marcado como suspeito com 10+ classes para a mesma base, 10+ ClassLoaders, ou 200+ JDK proxies: sinal de proxy recriado a cada uso em vez de cacheado, causa comum de `OutOfMemoryError: Metaspace`. Correção típica: gerar a classe uma vez e reutilizar (`TypeCache` do ByteBuddy, `Enhancer`/`ProxyFactory` com cache) e não criar um ClassLoader por chamada. O hprof não traz o tamanho do metaspace, só as classes.

### Histograma de classes
Uma linha por classe (arrays incluídos): número de instâncias, shallow total e retained size. O gráfico mostra as 20
maiores classes por retained, shallow ou número de instâncias (seletor acima do gráfico).

O retained de uma classe soma o retained das instâncias que não são dominadas por outra instância da mesma classe.
Isso evita que uma lista encadeada seja contada uma vez por nó.

### Memória por pacote
Shallow e retained somados por pacote Java. Arrays primitivos (`byte[]`, `int[]`...) têm grupo próprio, e classes sem
pacote ficam em `(default)`.

### Dominator tree
O treemap mostra o topo da dominator tree: cada retângulo é um objeto, a área é o retained size e os retângulos dentro
dele são os objetos que ele domina. Clique para aproximar; use o breadcrumb abaixo para voltar. Só os maiores filhos
são desenhados (100 no primeiro nível, 15 no segundo, 8 no terceiro).

### Maiores objetos (retained)
Objetos com maior retained size, com id, classe, shallow e, para strings e threads, o valor ou o nome.

### Caminhos até GC roots
Para cada suspeito (os 10 maiores objetos que não são classes, mais as instâncias de `--leak-class`), a menor cadeia de
referências fortes desde um GC root até ele. Escolha o objeto no seletor; o grafo vai do GC root, no topo, até o
objeto, embaixo, e cada nó mostra o campo que aponta para ele.

As cores dos nós vêm dos `ObjectInspectors` do Shark para o JDK:

| Status | Significado |
| --- | --- |
| `NOT_LEAKING` (verde) | O Shark sabe que é esperado que o objeto esteja vivo (por exemplo, uma thread ativa ou um class loader). |
| `LEAKING` (vermelho) | Um objeto que o Shark sabe que já deveria ter sido coletado. |
| `ALVO` (azul) | O objeto escolhido (maior retained ou `--leak-class`): é o fim do caminho, não um vazamento comprovado. |
| `UNKNOWN` (cinza) | Nenhuma regra se aplica. |

Para achar um vazamento, procure a primeira referência do caminho que não deveria existir: em geral um campo
estático, um cache ou um listener que nunca é removido.

### GC roots por tipo
Quantidade de GC roots por tipo: `StickyClass` (classes carregadas pelo bootstrap loader), `JavaFrame` (variáveis
locais), `ThreadObject`, `JniGlobal`, `MonitorUsed` etc.

### Threads
Cada thread com nome, flag daemon, prioridade, retained size e stack trace. Abaixo de cada frame, `local:` lista os
objetos presos pelas variáveis locais daquele frame.

### Strings duplicadas
Valores de `String` que aparecem mais de uma vez, ordenados pela memória desperdiçada
(`(cópias - 1) × bytes de uma cópia`). Strings com mais de 1024 caracteres são ignoradas, e os valores são cortados em
200 caracteres.

### Maiores arrays
Maiores object arrays e primitive arrays, com tamanho (elementos), bytes e retained size.

### ClassLoaders
Cada instância de class loader com o número de classes que ela definiu e o retained size. `<bootstrap>` representa o
loader nativo da JVM.

### Comparação entre dumps
Só com `--baseline`. Gere um snapshot de cada dump com `--format json` e passe os anteriores ao analisar o mais
recente:

```bash
java -jar hprof-analyzer-all.jar dia1.hprof --format json
java -jar hprof-analyzer-all.jar dia2.hprof --format json
java -jar hprof-analyzer-all.jar dia3.hprof --baseline dia1.snapshot.json,dia2.snapshot.json
```

Os snapshots são ordenados pelo timestamp do dump. A seção mostra a variação (Δ = atual − primeiro) de bytes por
classe e por pacote e de elementos das maiores coleções, numa barra divergente (cresceu × diminuiu) e na evolução de
um item escolhido. "Cresce" marca o que está presente em todos os dumps e aumenta a cada um. Como os IDs dos objetos
mudam entre dumps, as coleções são casadas pela assinatura do caminho a partir do GC root (ex.:
`[StickyClass] class X → X.cache (java.util.HashMap)`). O painel de saúde alerta quando coleções crescem em todos os
dumps (a partir de 3) e quando o heap cresce mais de 20%.

### Painel de saúde
Primeira seção do relatório completo. Regras automáticas com severidade (crítico, alerta, info) e link para a seção:
um objeto retendo mais de 30% do heap alcançável, fila do Finalizer acima de 10 mil, coleções grandes com fill ratio
médio abaixo de 25%, 2 ou mais ClassLoaders de webapp (Tomcat/Jetty), strings duplicadas (todas, não só o top N) acima de 10% do heap alcançável, grupos
de proxies suspeitos, coleções vazias com array alocado acima de 5% do heap, off-heap (`DirectByteBuffer`) maior que o
heap alcançável, 10 ou mais pools da mesma classe e pools com `max = Integer.MAX_VALUE` e fila ilimitada (exceto
`ScheduledThreadPoolExecutor`, em que isso é o comportamento previsto; a coluna "fila ilimitada" da tabela de pools
descreve só o tipo da fila). Os limiares
ficam em `Health.kt` e podem ser alterados com `--threshold`. Cada achado mostra o limiar que ultrapassou, e uma lista recolhida no painel traz os limiares de todas as regras, inclusive as que não dispararam.

### Suspeitas de leak
Uma única análise do Shark (`FilteringLeakingObjectFinder`) com regras de JVM: thread terminada ainda referenciada,
ClassLoader de webapp do Tomcat parado (`STOPPED`/`DESTROYED`), sessão HTTP inválida, `FileInputStream`/
`FileOutputStream`/`RandomAccessFile` fechados e retidos, e as classes de `--leak-class`. Os caminhos são agrupados
pela assinatura do Shark (N ocorrências do mesmo padrão). Caminhos que passam por referências conhecidas do JDK
(`Thread.contextClassLoader`, valor de `ThreadLocal`, `DriverManager.registeredDrivers`, shutdown hooks) viram
"biblioteca". O retained de cada grupo é a estimativa do Shark, calculada sobre a árvore de menores caminhos dele, e
pode diferir da dominator tree. Threads internas da JVM (`C1/C2 CompilerThread*`, `Service Thread` etc.) não contam
como thread terminada: a JVM as recria e o objeto fica preso por referência nativa. Caminhos sem referência suspeita
(o Shark daria a todos a mesma assinatura, o SHA-1 de `""`) são agrupados por classe do objeto + tipo do GC root.

### Caminhos agregados por classe
Sankey dos menores caminhos (BFS a partir dos GC roots) até as instâncias das 5 maiores classes e das classes de
`--leak-class`, agregados por classe, como o "merge shortest paths" do MAT. Até 10 mil instâncias e 8 saltos por classe.

### Retained agregado
Sunburst ClassLoader → pacote → classe; campos estáticos cujo valor é dominado pela própria classe (caches estáticos e
singletons); e, para as 10 maiores classes, a classe do dominador imediato das instâncias ("quem segura X").

### Concorrência
Estado de cada thread pelo campo `threadStatus` (o hprof não guarda monitores), `ThreadPoolExecutor` (core, máx,
threads, tipo e tamanho da fila), `ForkJoinPool`, `Timer`, virtual threads e o retained das continuations, classes dos
valores em `ThreadLocal`, stacks idênticas agrupadas e um sunburst dos frames a partir do topo da stack. A tabela de
threads ganhou estado, retained de locals, retained e contagem de `ThreadLocal`s e entradas órfãs (chave coletada).

### Frameworks e tecnologias
Inspetores que só aparecem quando as classes estão no dump, lendo campos internos de cada biblioteca:
- **Sessões HTTP** (Tomcat `StandardSession`, Jetty): quantidade, expiradas/inválidas ainda retidas, maiores sessões e
  retained por nome de atributo.
- **Hibernate**: contextos de persistência (`StatefulPersistenceContext`) e entidades gerenciadas em cada um.
- **Spring**: singletons de cada `DefaultListableBeanFactory` e os beans mais pesados.
- **JDBC**: pools HikariCP e DBCP2 (conexões, ociosas, ativas) e quantidade de `Statement`/`ResultSet` dos drivers.
- **Caches**: Caffeine, Guava, Ehcache e mapas estáticos com 100 ou mais entradas.
- **Jackson**: quantidade de `ObjectMapper` (muitos indicam criação por requisição).
- **Exceções retidas**: `Throwable` agrupados por tipo e mensagem, com retained (inclui o `backtrace`). As
  pré-alocadas (o menor caminho passa por campo estático ou começa num GC root que não é de thread, como os
  `OutOfMemoryError` que a JVM cria na inicialização ou constantes do H2) ficam numa tabela à parte, recolhida. Uma
  exceção da aplicação guardada em campo estático também cai nessa tabela.

Versões muito diferentes das bibliotecas podem deixar valores em branco; uma falha num inspetor vira aviso e não
derruba os outros.

### Estrutura do grafo
Objetos mais referenciados (fan-in) e com mais referências de saída (fan-out), histograma de profundidade até GC root
(menor caminho) e grafo circular das 50 referências mais frequentes entre classes. As arestas instância → classe,
adicionadas só para manter classes vivas, ficam de fora.

### Metadados do arquivo
Contagem de registros de nível superior do hprof por tipo (lidos no mesmo passe dos stack traces), razão tamanho do
arquivo / shallow total, coletores de GC registrados como MXBean no heap e flags de memória/GC quando os argumentos da
JVM estão no heap.

### Desperdício de memória
- **Coleções**: `ArrayList`, `Vector`, `HashMap`, `LinkedHashMap`, `WeakHashMap`, `Hashtable`, `ConcurrentHashMap` e
  `ArrayDeque` (só as classes exatas; `HashSet` aparece como o seu `HashMap` interno). Por tipo: vazias com array
  alocado, elementos, capacidade e bytes de slots livres. Gráficos de fill ratio e de número de elementos.
- **Arrays**: primitive arrays todos zero (≥ 64 B), object arrays todo null ou ≥ 90% null, e primitive arrays de
  conteúdo idêntico (≥ 256 B, hash de 64 bits; arrays de Strings ficam de fora porque já estão em strings duplicadas).
- **Boxing**: instâncias de `Integer`, `Long`, `Boolean`... "Redundantes" são cópias de um valor que a JVM mantém em
  cache (-128..127, `true`/`false`): indicam `new Integer(...)`.
- **Campos quase sempre null**: classes com ≥ 1000 instâncias e campos de referência ≥ 90% null (heatmap).
- **Header e padding**: estimativa para JVM 64-bit com compressed class pointers (header 12 B, arrays 16 B,
  alinhamento 8 B), já que o hprof não guarda header.
- **Strings**: LATIN1 × UTF16 (campo `coder`; JDK 8 conta como UTF16), vazias, distribuição de tamanho e prefixos
  mais comuns.

### Referências e finalização
Contagem de soft, weak, phantom e final references, referents ainda presentes e seus bytes; classes de `Reference`;
tamanho da fila do `Finalizer` e classes registradas para finalização (o hprof não guarda métodos, então elas vêm dos
referents de `java.lang.ref.Finalizer`); `Cleaner`s. O tile **Não alcançável** do resumo inclui objetos que só são
alcançáveis por weak/soft refs.

### Memória off-heap (estimada)
O dump não contém memória nativa; ela é estimada pelos objetos Java: capacidade de `DirectByteBuffer` (donos, views
e mapeados), `PoolChunk` do Netty, e contagem de descritores, streams, sockets, `ZipFile`, `Inflater`/`Deflater`
(com "abertos" quando a classe tem campo `closed`/`fd`). Views (`slice()`, `duplicate()`) compartilham a memória do
buffer dono e não devem ser somadas: um `duplicate()` de um buffer grande tem a mesma capacidade que ele.

## 6. Conceitos

**Shallow size**: memória usada pelo próprio objeto (seus campos ou, num array, seus elementos).

**Retained size**: memória que seria liberada se o objeto fosse coletado: o próprio objeto mais tudo o que só é
alcançável através dele.

**Dominador**: o objeto A domina o objeto B quando todo caminho de um GC root até B passa por A. O retained size de A é
a soma dos shallow sizes de todos os objetos que A domina.

**GC root**: referência que o garbage collector sempre considera viva: variáveis locais de threads em execução, campos
estáticos (por meio da sua classe), referências JNI, monitores em uso etc.

**Força da referência**: `WeakReference`, `SoftReference` e `PhantomReference` não mantêm o alvo vivo, então o
analisador as ignora. Objetos alcançáveis só por elas (por exemplo, caches baseados em `SoftReference`) contam como
inalcançáveis e não entram em nenhum retained size.

**Convenção de tamanhos**: os tamanhos são estimados como a JVM HotSpot 64-bit aloca: header de 12 bytes (arrays 16),
alinhamento de 8 bytes e referências de 4 bytes quando compressed oops está ativo (detectado por
`java.vm.compressedOopsMode`; senão 8). O hprof grava toda referência com 8 bytes e sem header, o que superestimava
objetos cheios de referências. O modelo usado aparece no resumo. Snapshots de versões anteriores usavam os bytes crus do
hprof; a comparação entre dumps avisa quando os modelos diferem.

**Retained não é somável**: o retained de um pacote ou classe inclui objetos de outros pacotes e classes que ele
domina, então os valores se sobrepõem. As colunas marcadas "(sobrepõe)" não devem ser somadas.

## 7. Desempenho e memória

- Dê à JVM cerca de 1,5–2× o tamanho do dump: `java -Xmx8g -jar ...` para um dump de 4 GB.
- Referência: um dump de 160 MB com 1,6 milhão de objetos leva cerca de 40 s (cerca de 20 s com `--no-retained`).
- O processamento é basicamente single-thread. Os maiores custos são ler cada objeto uma vez e calcular a dominator
  tree.
- Cada caminho até GC root é uma busca separada, então uma lista longa em `--leak-class` deixa a análise mais lenta.
- O relatório HTML tem cerca de 1,2 MB (ECharts) mais os dados; `--top` controla quantos dados são embutidos.

## 8. Idiomas (i18n)

A opção `--i18n <código>` escolhe o idioma dos relatórios (Markdown e HTML) e das mensagens do console.

| Código | Idioma |
| --- | --- |
| `pt-BR` | Português (Brasil), padrão |
| `en` | Inglês |

Como o código é resolvido:

1. Maiúsculas e separador são normalizados: `pt_br`, `PT-BR` e `pt-BR` são equivalentes.
2. Primeiro tenta o código exato, depois só o idioma: `en-US` usa `en` quando não há arquivo `en-US`.
3. Código desconhecido mostra um aviso e usa `pt-BR`.
4. Chave ausente num arquivo de idioma usa o texto de `pt-BR`.

Os números seguem o locale escolhido (`1,638,523` em inglês, `1.638.523` em português).

Nomes de classe, tipos de GC root e os textos de status do Shark são dados, não textos de interface, e nunca são
traduzidos.

### Adicionando um idioma

1. Copie `src/main/resources/i18n/messages_en.properties` para `messages_<código>.properties`, onde `<código>` é uma
   tag de idioma [BCP 47](https://www.rfc-editor.org/info/bcp47) (`es`, `fr`, `de`, `pt-PT`...).
2. Traduza os valores. Mantenha as chaves e os marcadores `{0}`, `{1}` como estão.
3. Salve o arquivo em **UTF-8**.
4. Em `cli.usage`, as linhas de continuação começam com `\ ` (barra invertida e espaço) para manter a indentação.
5. Inclua o código na lista `languages` de `src/test/kotlin/I18nTest.kt` e rode `./gradlew test`. O teste falha se
   faltar alguma chave no novo arquivo ou se ele tiver uma chave que não existe em `pt-BR`.
6. Gere o jar de novo e rode com `--i18n <código>`.

Não é preciso mudar código: os arquivos são procurados pelo nome em tempo de execução.

Grupos de chaves: `cli.*` (uso e erros), `log.*` (progresso), `report.*`, `section.*`, `note.*`, `summary.*`, `col.*`
(cabeçalhos de tabela), `chart.*`, `paths.*`, `misc.*`.

## 9. Arquitetura

| Arquivo | Papel |
| --- | --- |
| `src/main/kotlin/Main.kt` | Lê os argumentos, carrega o idioma, roda a análise e grava os arquivos. |
| `src/main/kotlin/Analyzer.kt` | Abre o dump com o Shark e monta o modelo de dados `HeapReport`. |
| `src/main/kotlin/Dominators.kt` | Algoritmo da dominator tree. |
| `src/main/kotlin/Waste.kt`, `References.kt`, `OffHeap.kt` | Coletores chamados na passada única; cada um vira uma seção. |
| `src/main/kotlin/Leaks.kt` | Caminhos por suspeito e detecção de leaks (regras de JVM, padrões de biblioteca). |
| `src/main/kotlin/Paths.kt` | Árvore BFS dos GC roots e caminhos agregados por classe. |
| `src/main/kotlin/Retained.kt` | Retained por ClassLoader, campos estáticos e dominadores imediatos por classe. |
| `src/main/kotlin/Threads.kt` | Threads, pools, virtual threads, ThreadLocals, stacks agrupadas. |
| `src/main/kotlin/Graph.kt` | Fan-in/fan-out, profundidade e arestas entre classes. |
| `src/main/kotlin/Frameworks.kt` | Inspetores de sessões, Hibernate, Spring, JDBC, caches, Jackson e exceções. |
| `src/main/kotlin/Health.kt` | Regras do painel de saúde (função pura sobre o `HeapReport`). |
| `src/main/kotlin/Snapshot.kt` | Snapshot JSON com o histograma completo e as coleções por caminho (`--format json`). |
| `src/main/kotlin/Diff.kt` | Comparação com snapshots anteriores (`--baseline`). |
| `src/main/kotlin/Reports.kt` | Geração do Markdown e do HTML. |
| `src/main/kotlin/I18n.kt` | Carrega os arquivos de idioma e formata as mensagens. |
| `src/main/resources/report.html` | Template HTML: CSS e o JavaScript que desenha tabelas e gráficos. |
| `src/main/resources/echarts.min.js` | Apache ECharts, embutido em todo relatório HTML. |
| `src/main/resources/i18n/` | Arquivos de idioma. |
| `src/main/resources/version.properties` | Preenchido no build com o `version` do `build.gradle.kts`; lido por `VERSION` em `Main.kt`. |

Para lançar uma nova versão, altere `version` no `build.gradle.kts`; o número não fica em nenhum outro lugar.

Etapas da análise:

1. O Shark indexa o dump (todos os tipos de GC root são indexados, não só o subconjunto padrão do Shark).
2. Uma única passada por todos os objetos monta o histograma de classes, a contagem de strings duplicadas, a lista de
   class loaders e o grafo de referências, e alimenta os coletores de desperdício, referências e off-heap (uma segunda
   passada lê só o conteúdo dos primitive arrays). Referents de weak, soft e phantom references são ignorados, e toda instância
   também referencia a sua classe.
3. Uma raiz virtual é ligada a todos os GC roots, e a dominator tree do grafo inteiro é calculada com o algoritmo
   **Semi-NCA** (semidominadores de Lengauer–Tarjan e ancestral comum mais próximo). Ele é iterativo e usa só arrays
   de `int`, então escala para dezenas de milhões de objetos. O Shark 2.14 tem uma dominator tree própria, mas ela é
   interna à biblioteca.
4. Os retained sizes são acumulados de baixo para cima na árvore.
5. O `HeapAnalyzer` do Shark encontra o menor caminho até GC root de cada suspeito e aplica os `ObjectInspectors` do JDK.
6. Os stack traces das threads vêm dos registros `STACK TRACE` e `STACK FRAME` do hprof.
7. O `HeapReport` é escrito em Markdown, ou serializado em JSON e embutido no template HTML junto com o ECharts e os
   textos do idioma.

Notas de implementação:

- **Contorno para bug do Shark 2.14**: `HeapGraph.findObjectById` devolve um `objectIndex` errado para primitive arrays,
  e `findObjectByIndex` falha com eles. O analisador corrige o índice (`HeapGraph.indexOf` em `Analyzer.kt`) e mantém
  sua própria tabela índice → id.
- **Segurança do HTML**: o conteúdo do dump (strings, nomes de classe) não é confiável. Ele é embutido como JSON com
  `</` escapado, e a página o insere com `textContent`, nunca como HTML.

## 10. Limitações

- Os tamanhos são estimativas para HotSpot 64-bit com compressed class pointers e alinhamento de 8 bytes (veja a
  [seção 6](#6-conceitos)); `-XX:-UseCompressedClassPointers` ou `ObjectAlignmentInBytes` diferente não são detectados.
- Só dumps do HotSpot/OpenJDK foram testados. Dumps de Android são lidos pelo Shark, mas não foram testados.
- Os suspeitos são escolhidos pelo tamanho. A ferramenta não decide sozinha se algo é vazamento; quem mostra isso é o
  caminho até o GC root.
- O retained de uma classe ainda pode contar memória duas vezes quando instâncias dela se dominam através de objetos de
  outra classe.
- Dumps compactados (`.hprof.gz`) precisam ser descompactados antes.

## 11. Solução de problemas

| Problema | Solução |
| --- | --- |
| `OutOfMemoryError` durante a análise | Aumente o `-Xmx` ou use `--no-retained`. |
| Acentos trocados no console (Windows) | Rode `chcp 65001` antes, ou acrescente `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` ao comando `java`. Os arquivos de relatório são sempre UTF-8. |
| `Aviso: idioma "xx" não encontrado` | Não existe arquivo `messages_xx.properties`; veja [adicionando um idioma](#adicionando-um-idioma). |
| "Caminhos até GC roots" vazio | Os suspeitos são escolhidos pelo retained size. Remova `--no-retained` ou use `--leak-class`. |
| Grande parte do heap aparece como inalcançável | Em geral são objetos presos só por soft/weak references (caches), ou lixo num dump gerado com `-all`. |
| Gráficos não aparecem | Veja o console do navegador. O arquivo precisa ser aberto inteiro; ele não carrega nada da rede. |
