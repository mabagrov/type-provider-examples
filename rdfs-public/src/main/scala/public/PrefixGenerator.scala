package typeproviders.rdfs.public

import org.w3.banana.sesame.SesameModule
import scala.annotation.StaticAnnotation
import scala.language.experimental.macros
import scala.reflect.macros.Context
import typeproviders.rdfs.SchemaParserModule

class fromSchema(path: String) extends StaticAnnotation {
  def macroTransform(annottees: Any*) = macro PrefixGenerator.fromSchema_impl
}

object PrefixGenerator extends SchemaParserModule with SesameModule { gen =>
  def fromSchema_impl(c: Context)(annottees: c.Expr[Any]*) = {
    import c.universe._

    def bail(message: String) = c.abort(c.enclosingPosition, message)

    /** The expected usage will look something like this following:
      *
      * {{{
      * @fromSchema("/dcterms.rdf") object dc extends PrefixBuilder[Rdf] ...
      * }}}
      *
      * The argument to the annotation must be a string literal, since we need
      * to know its value at compile-time (i.e., now) in order to read and
      * parse the schema. The following code digs into the tree of the macro
      * application and confirms that we have a string literal.
      */
    val path = c.macroApplication match {
      case Apply(Select(Apply(_, List(Literal(Constant(s: String)))), _), _) =>
        s
      case _ => bail(
        "You must provide a literal resource path for schema parsing."
      ) 
    }

    annottees.map(_.tree) match {
      /** Note that we're checking that the body of the annotated object is
        * empty, since in this case it wouldn't make sense for the user to add
        * his or her own methods to the prefix object. For other kinds of type
        * providers this might be reasonable or even desirable. In these cases
        * you'd simply remove the check for emptiness below and add the body
        * to the definition you return.
        */
      case List(q"object $name extends $parent { ..$body }") if body.isEmpty =>
        /** The following few steps look exactly like what we did in the case
          * of the anonymous type providers.
          */
        val schemaParser = fromResource(path).getOrElse(
          bail(s"Invalid schema: $path.")
        )

        val baseUri = schemaParser.inferBaseUri.getOrElse(
          bail("Could not identify a unique schema URI.")
        )

        val baseUriString = gen.ops.fromUri(baseUri)

        val names =
          schemaParser.classNames(baseUri) ++
          schemaParser.propertyNames(baseUri)

        val defs = names.map { name =>
          q"val ${newTermName(name)} = apply($name)"
        }

        /** We assume here that the parent is [[org.w3.banana.PrefixBuilder]].
          * We could add some validation logic to confirm this, but the macro
          * is likely to fail in a pretty straightforward way if it's not the
          * case, so we'll leave it like this for the sake of simplicity.
          */
        c.Expr[Any](
          q"""
            object $name extends $parent(${name.decoded}, $baseUriString) {
              ..$defs
            }
          """
        )

      case _ => bail(
        "You must annotate an object definition with an empty body."
      )
    }
  }
}

