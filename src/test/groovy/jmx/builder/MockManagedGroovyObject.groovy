package groovy.jmx.builder

class MockManagedGroovyObject {
  int id
  String name
  def location

  def doSomething() {
    // do sometihng
  }

  def doSomethingElse() {
    // do something else
  }

  static descriptor = [
          name: "jmx.builder:type=EmbeddedObject"
  ]
}