# Jekyll ScalaFiddle

Integrate ScalaFiddle easily into your Jekyll documentation using this plugin.

## Installation

1. Add the following to your site's `Gemfile`:

```ruby
gem 'jekyll-scalafiddle'
```

2. Add the following to your site's `_config.yml`:

```yml
plugins:
  - jekyll-scalafiddle
```

### Installation for sbt-microsites

[sbt-microsites](https://47deg.github.io/sbt-microsites/) is a plugin for sbt that helps Scala developers build and publish 
documentation for their project. It's based on Jekyll, so you can use this same plugin. Unlike regular Jekyll, you cannot use 
`gem` to install the plugin, but you must manually copy the `jekyll-scalafiddle.rb` file under a `_plugins` directory in your 
microsite project (eg in `docs/` by default for an mdoc project).

```bash
$ mkdir _plugins
$ cd _plugins
$ curl -o jekyll-scalafiddle.rb https://raw.githubusercontent.com/scalafiddle/scalafiddle-core/master/integrations/jekyll/lib/jekyll-scalafiddle.rb
```

## Usage

The ScalaFiddle plugin provides a tag `scalafiddle` which you can use to convert a code block in your documentation into an
editable and runnable fiddle. Simply surround a code block with the tag as shown below.

Before:
````
```scala
def sum(a: Int, b: Int) = a + b

println(sum(2, 2))
```
````

After:
````
{% scalafiddle %}
```scala
def sum(a: Int, b: Int) = a + b

println(sum(2, 2))
```
{% endscalafiddle %}
````

This will instruct the plugin to generate a special `<div>` around your code which will turn it into an editable fiddle when the
viewer clicks the `Run` button.

![Code block with a Run button](../gitbook/images/fiddle_run.png)

You can also wrap multiple code blocks with explanatory text in between them. The blocks will be joined
into a single fiddle and all extra text is simply dropped.

````
{% scalafiddle %}
```scala
def sum(a: Int, b: Int) = a + b
```
Then we call the method:
```scala
println(sum(2, 2))
```
{% endscalafiddle %}
````

### Parameters

Each fiddle can be further customized with parameters. These parameters are described in more detail in the 
[main integration documentation](https://github.com/scalafiddle/scalafiddle-core/blob/master/integrations/README.md). For example:

````
{% scalafiddle prefix="import scala.util.Random" theme="dark" %}
````

### Templates

Each ScalaFiddle consists of a "user visible" part and of a template that is hidden from the user. You can use this template
code to provide additional or common functionality without cluttering each of your fiddles. A template can contain code that 
comes before the user code, or also code that comes afterwards (separated by a line starting with four slashes `////`).

The example template below wraps the user's code into a `{}` block, assigns it to a `val` and prints the result at the end.

```scala
val result = {
////
}
println(result)
```

Templates are stored by default under the `_scalafiddle` directory (which can be changed via configuration). Each template must
have a `.scala` extension. For the example above, the correct file name would be `_scalafiddle/Result.scala`

Use the template in documentation
````
{% scalafiddle template="Result" %}
```scala
def sum(a: Int, b: Int) = a + b
sum(2, 2)
```
{% endscalafiddle %}
````

You will see only the user defined code in the fiddle. The result pane, however, shows you that the template code was also
run.

![Using a template](../gitbook/images/use_template.png)

The final code executed will actually be:

```scala
val result = {
def sum(a: Int, b: Int) = a + b
sum(2, 2)
}
println(result)
```

## Configuration

The ScalaFiddle plugin can be configured the same as any other Jekyll plugin via `_config.yaml`. For example:

```yaml
scalafiddle:
  dependency: io.circe %%% circe-core % 0.8.0,io.circe %%% circe-generic % 0.8.0,io.circe %%% circe-parser % 0.8.0
  scalaFiddleUrl: http://localhost:8880/
```

In the configuration you can provide default values for fiddle parameters such as `dependency`, `minheight`, `theme` etc. 
Typically you would use the `dependency` configuration in a library documentation where each code example has the same dependency
to your library.

You can also configure the location of templates with `templateDir` and the URL for the ScalaFiddle service (if you want to
run your own ScalaFiddle server, for example locally) using `scalaFiddleUrl`.

### Configuration with sbt-microsites

To configure the ScalaFiddle plugin, use the `micrositeConfigYaml` option in your project definition. For example: 

```scala
micrositeConfigYaml := ConfigYml(yamlInline =
"""
  |scalafiddle:
  |  dependency: io.circe %%% circe-core % 0.8.0,io.circe %%% circe-generic % 0.8.0,io.circe %%% circe-parser % 0.8.0
  |  scalaFiddleUrl: http://localhost:8880/
  """.stripMargin)
```
