package au.com.dius.pact.model

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Slf4j

import java.nio.channels.FileLock
import java.util.jar.JarInputStream

/**
 * Base Pact class
 */
@SuppressWarnings(['AbstractClassWithoutAbstractMethod', 'SpaceAroundMapEntryColon'])
@Slf4j
@ToString
@EqualsAndHashCode(excludes = ['metadata'])
abstract class BasePact implements Pact {
  protected static final Map DEFAULT_METADATA = [
    'pact-specification': ['version': '3.0.0'],
    'pact-jvm'          : ['version': lookupVersion()]
  ]
  private static final String METADATA = 'metadata'

  Consumer consumer
  Provider provider
  Map metadata = DEFAULT_METADATA

  protected BasePact(Provider provider, Consumer consumer, Map metadata) {
    this.consumer = consumer
    this.provider = provider
    this.metadata = metadata
  }

  static String lookupVersion() {
    def url = BasePact.protectionDomain?.codeSource?.location
    if (url != null) {
      def openStream = url.openStream()
      try {
        def jarStream = new JarInputStream(openStream)
        jarStream.manifest?.mainAttributes?.getValue('Implementation-Version') ?: ''
      } catch (e) {
        log.warn('Could not load pact-jvm manifest', e)
        ''
      } finally {
        openStream.close()
      }
    } else {
      ''
    }
  }

  static Map objectToMap(def object) {
    if (object?.respondsTo('toMap')) {
      object.toMap()
    } else {
      convertToMap(object)
    }
  }

  static Map convertToMap(def object) {
    if (object == null) {
      object
    } else {
      object.properties.findAll { it.key != 'class' }.collectEntries { k, v ->
        if (v instanceof Map) {
          [k, convertToMap(v)]
        } else if (v instanceof Collection) {
          [k, v.collect { convertToMap(v) } ]
        } else {
          [k, v]
        }
      }
    }
  }

  static parseBody(HttpPart httpPart) {
    if (httpPart.jsonBody() && httpPart.body.present) {
      def body = new JsonSlurper().parseText(httpPart.body.value)
      if (body instanceof String) {
        httpPart.body.value
      } else {
        body
      }
    } else {
      httpPart.body.value
    }
  }

  static String mapToQueryStr(Map<String, List<String>> query) {
    query.collectMany { k, v -> v.collect { "$k=${URLEncoder.encode(it, 'UTF-8')}" } }.join('&')
  }

  @SuppressWarnings(['ConfusingMethodName'])
  static Map metaData(String version) {
    [
      'pact-specification': [version: version],
      'pact-jvm': [version: lookupVersion()]
    ]
  }

  @CompileStatic
  void write(String pactDir, PactSpecVersion pactSpecVersion) {
    def pactFile = fileForPact(pactDir)
    if (pactFile.exists()) {
      synchronized (pactFile) {
        RandomAccessFile raf = new RandomAccessFile(pactFile, 'rw')
        FileLock lock = raf.channel.lock()
        try {
          def existingPact = PactReader.loadPact(pactFile)
          def result = PactMerge.merge(existingPact, this)
          if (!result.ok) {
            throw new InvalidPactException(result.message)
          }
          pactFile.withWriter { it.print(JsonOutput.prettyPrint(this.toJson(pactSpecVersion))) }
        } finally {
          lock.release()
        }
      }
    } else {
      pactFile.parentFile.mkdirs()
      pactFile.withWriter { it.print(JsonOutput.prettyPrint(this.toJson(pactSpecVersion))) }
    }
  }

  @CompileStatic
  private String toJson(PactSpecVersion pactSpecVersion) {
    def jsonMap = toMap(pactSpecVersion)
    if (jsonMap.containsKey(METADATA)) {
      def map = [:] + DEFAULT_METADATA
      map.putAll(jsonMap[METADATA] as Map)
      jsonMap.put(METADATA, map)
    } else {
      jsonMap.put(METADATA, DEFAULT_METADATA)
    }
    JsonOutput.toJson(jsonMap)
  }

  Map mergePacts(Map pact, File pactFile) {
    Map newPact = [:] + pact
    def json = new JsonSlurper().parse(pactFile)

    def pactSpec = 'pact-specification'
    def version = json?.metadata?.get(pactSpec)?.version
    def pactVersion = pact.metadata?.get(pactSpec)?.version
    if (version && version != pactVersion) {
      throw new InvalidPactException("Could not merge pact into '$pactFile': pact specification version is " +
        "$pactVersion, while the file is version $version")
    }

    if (json.interactions != null) {
      throw new InvalidPactException("Could not merge pact into '$pactFile': file is not a message pact " +
        '(it contains request/response interactions)')
    }

    newPact.messages = (newPact.messages + json.messages).unique { it.description }
    newPact
  }

  File fileForPact(String pactDir) {
    new File(pactDir, "${consumer.name}-${provider.name}.json")
  }

  boolean compatibleTo(Pact other) {
    provider == other.provider && this.class.isAssignableFrom(other.class)
  }
}
