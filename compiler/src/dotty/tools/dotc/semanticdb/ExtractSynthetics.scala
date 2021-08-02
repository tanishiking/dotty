package dotty.tools.dotc.semanticdb

import dotty.tools.dotc.ast.tpd._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.{semanticdb => s}

import scala.collection.mutable

class SyntheticsExtractor:
  import Scala3.{_, given}

  def tryFindSynthetic(tree: Tree)(using Context, SemanticSymbolBuilder, TypeOps): Option[s.Synthetic] =
    extension (synth: s.Synthetic)
      def toOpt: Some[s.Synthetic] = Some(synth)

    if tree.span.isSynthetic || tree.symbol.isInventedGiven then
      tree match
        case tree: Apply if isForSynthetic(tree) =>
          None // not yet supported (for synthetics)
        case tree: Apply
          if tree.args.nonEmpty &&
            tree.args.forall(arg =>
              arg.symbol.isOneOf(GivenOrImplicit) &&
              arg.span.isSynthetic
            ) =>
          s.Synthetic(
            range(tree.span, tree.source),
            s.ApplyTree(
              tree.fun.toSemanticOriginal,
              tree.args.map(_.toSemanticTree)
            )
          ).toOpt

        // Anonymous context parameter
        case tree: ValDef if tree.symbol.is(Given) =>
          s.Synthetic(
            range(tree.span, tree.source),
            tree.toSemanticId
          ).toOpt
        case _ => None
    else None

  extension (tree: Tree)
    private def toSemanticTree(using Context, SemanticSymbolBuilder, TypeOps): s.Tree =
      tree match
        case tree: Apply =>
          s.ApplyTree(
            tree.fun.toSemanticQualifierTree,
            tree.args.map(_.toSemanticTree)
          )
        case tree: TypeApply =>
          s.TypeApplyTree(
            tree.fun.toSemanticQualifierTree,
            tree.args.map { targ =>
              targ.tpe.toSemanticType(targ.symbol)(using LinkMode.SymlinkChildren)
            }
          )
        case tree: Ident => tree.toSemanticId
        case tree: Select => tree.toSemanticId
        case _ => s.Tree.defaultInstance

    private def toSemanticQualifierTree(using Context, SemanticSymbolBuilder): s.Tree = tree match
      case sel @ Select(qual, _) if sel.symbol.owner != qual.symbol =>
        s.SelectTree(qual.toSemanticId, Some(sel.toSemanticId))
      case fun => fun.toSemanticId

    private def toSemanticId(using Context, SemanticSymbolBuilder) =
      s.IdTree(tree.symbol.symbolName)

    private def toSemanticOriginal(using Context) =
      s.OriginalTree(range(tree.span, tree.source))


  def isForSynthetic(tree: Tree): Boolean =
    def isForComprehensionSyntheticName(select: Select): Boolean =
      select.span.toSynthetic == select.qualifier.span.toSynthetic && (
        select.name == nme.map ||
        select.name == nme.flatMap ||
        select.name == nme.withFilter ||
        select.name == nme.foreach
      )
    tree match
      case Apply(fun, _) => isForSynthetic(fun)
      case TypeApply(fun, _) => isForSynthetic(fun)
      case select: Select => isForComprehensionSyntheticName(select)
      case _ => false

end SyntheticsExtractor
