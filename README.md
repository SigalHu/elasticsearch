### 编译调试 elasticsearch v5.6.0

1. `git clone https://github.com/SigalHu/elasticsearch.git`
2. 在源码根目录执行 `gradle idea`，注意，gradle 版本为 3.5，更高版本可能会报错
3. 找到主类 `org.elasticsearch.bootstrap.Elasticsearch`，配置 Run/Debug Configurations，VM Options: `-Dlog4j2.disable.jmx=true -Des.path.home=$PROJECT_DIR$/core`
4. 配置 JDK8<br>
![](img/jdk%20for%20es.png)
