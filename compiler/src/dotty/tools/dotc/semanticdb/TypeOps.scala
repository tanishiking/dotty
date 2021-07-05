package dotty.tools
package dotc
package semanticdb

import core.Symbols._
import core.Contexts.Context
import core.Types._
import core.Annotations.Annotation
import core.Flags
import core.Names.{Name, TypeName}
import core.StdNames.tpnme
import ast.tpd._

import collection.mutable

import dotty.tools.dotc.{semanticdb => s}

class TypeOps:
  import SymbolScopeOps._
  import Scala3.given
  private val paramRefSymtab = mutable.Map[(LambdaType, Name), Symbol]()
  private val refinementSymtab = mutable.Map[(RefinedType, Name), Symbol]()
  given typeOps: TypeOps = this
  extension (tpe: Type)
    def toSemanticSig(using LinkMode, Context, SemanticSymbolBuilder)(sym: Symbol): s.Signature =
      def enterParamRef(tpe: Type): Unit =
        tpe match {
          case lam: LambdaType =>
            // find the real owner type
            if lam.paramNames.contains(sym.name) then
              paramRefSymtab((lam, sym.name)) = sym
            else
              enterParamRef(lam.resType)

          // for CaseType `case Array[t] => t` which is represented as [t] =>> MatchCase[Array[t], t]
          case m: MatchType =>
            m.cases.foreach(enterParamRef)

          // for class constructor
          // class C[T] { ... }
          case cls: ClassInfo if sym.info.isInstanceOf[LambdaType] =>
            val lam = sym.info.asInstanceOf[LambdaType]
            cls.cls.typeParams.foreach { param =>
              paramRefSymtab((lam, param.name)) = param
            }

          // type X[T] = ...
          case tb: TypeBounds =>
            enterParamRef(tb.lo)
            enterParamRef(tb.hi)

          case _ => ()
        }

      def enterRefined(tpe: Type): Unit =
        tpe match {
          case refined: RefinedType =>
            val key = (refined, sym.name)
            refinementSymtab(key) = sym

          case rec: RecType =>
            enterRefined(rec.parent)

          case cls: ClassInfo
            if (cls.cls.name == tpnme.REFINE_CLASS) =>
            sym.owner.owner.info match {
              // def foo(x: Parent { refinement }) = ...
              case refined: RefinedType =>
                enterRefined(refined)

              // type x = Person { refinement }
              case tb: TypeBounds =>
                // tb = TypeBounds(
                //   lo = RefinedType(...)
                //   hi = RefinedType(...)
                // )
                enterRefined(tb.lo)
                enterRefined(tb.hi)

              // def s(x: Int): { refinement } = ...
              case expr: ExprType =>
                enterRefined(expr.resType)
              case m: MethodType =>
                enterRefined(m.resType)
              case _ => ()
            }
          case _ => ()
        }
      enterParamRef(sym.owner.info)
      enterRefined(sym.owner.info)

      def loop(tpe: Type): s.Signature = tpe match {
        case mt: MethodType =>
          val stparams = Some(s.Scope())
          val paramss =
            if (sym.rawParamss.nonEmpty) sym.rawParamss else sym.paramSymss
          val termParamss = paramss.filter(ps => ps.forall(!_.isTypeParam))
          val sparamss = termParamss.map(_.sscope)
          s.MethodSignature(
            stparams,
            sparamss,
            mt.resType.toSemanticType(sym)
          )

        case cls: ClassInfo =>
          val stparams =
            if (cls.cls.typeParams.nonEmpty)
              Some(cls.cls.typeParams.sscope)
            else None
          val sparents = cls.parents.map(_.toSemanticType(sym))
          val sself = cls.selfType.toSemanticType(sym)
          val decls = cls.decls.toList.sscope
          s.ClassSignature(stparams, sparents, sself, Some(decls))

        case TypeBounds(lo, hi) =>
          // oops we have no way to distinguish
          // type X[T] = T and type X = [T] =>> T

          // for `type X[T] = T` is equivalent to `[T] =>> T`
          // println("===")
          // println(sym)
          // println(sym.flagsString)
          // def tparams(tpe: Type): (Type, List[Symbol]) = tpe match {
          //   case lambda: HKTypeLambda =>
          //     lambda.dealias
          //     val paramSyms = lambda.paramNames.flatMap { paramName =>
          //       paramRefSymtab.get((lambda, paramName))
          //     }
          //     (lambda.resType, paramSyms)
          //   case _ => (tpe, Nil)
          // }
          // val (loRes, loParams) = tparams(lo)
          // val (hiRes, hiParams) = tparams(hi)
          // val params = (loParams ++ hiParams).distinctBy(_.name)
          val slo = lo.toSemanticType(sym)
          val shi = hi.toSemanticType(sym)
          // val stparams = params.sscope
          s.TypeSignature(Some(s.Scope()), slo, shi)

        case pt: PolyType =>
          loop(pt.resType) match {
            case m: s.MethodSignature =>
              val paramss =
                if (sym.rawParamss.nonEmpty) sym.rawParamss else sym.paramSymss
              val tparamss = paramss.filter(ps => ps.forall(_.isTypeParam))
              val stparams = tparamss.flatten.sscope
              m.copy(typeParameters = Some(stparams))
            case v: s.ValueSignature =>
              val paramss =
                if (sym.rawParamss.nonEmpty) sym.rawParamss else sym.paramSymss
              val tparamss = paramss.filter(ps => ps.forall(_.isTypeParam))
              val stparams = tparamss.flatten.sscope
              s.ValueSignature(s.UniversalType(Some(stparams), v.tpe))
            case _ => s.Signature.Empty
          }

        case other =>
          s.ValueSignature(
            other.toSemanticType(sym)
          )
      }
      loop(tpe)

    private def toSemanticType(using LinkMode, SemanticSymbolBuilder, Context)(sym: Symbol): s.Type =
      import ConstantOps._
      def loop(tpe: Type): s.Type = tpe match {
        case ExprType(tpe) =>
          val stpe = loop(tpe)
          s.ByNameType(stpe)

        case TypeRef(pre, desig) if desig.isInstanceOf[Symbol] =>
          val spre = if(tpe.hasTrivialPrefix) s.Type.Empty else loop(pre)
          val ssym = desig.asInstanceOf[Symbol].symbolName
          s.TypeRef(spre, ssym, Seq.empty)

        case TermRef(pre, desig) if desig.isInstanceOf[Symbol] =>
          val spre = if(tpe.hasTrivialPrefix) s.Type.Empty else loop(pre)
          val ssym = desig.asInstanceOf[Symbol].symbolName
          s.SingleType(spre, ssym)

        case tref: ParamRef =>
          val key = (tref.binder, tref.paramName)
          pprint.pprintln(key)
          pprint.pprintln(paramRefSymtab)
          paramRefSymtab.get(key) match {
            case Some(ref) =>
              val ssym = ref.symbolName
              tref match {
                case _: TypeParamRef => s.TypeRef(s.Type.Empty, ssym, Seq.empty)
                case _: TermParamRef => s.SingleType(s.Type.Empty, ssym)
              }
            case None => // shouldn't happen
              s.Type.Empty
          }

        case ThisType(TypeRef(_, desig)) if desig.isInstanceOf[Symbol] =>
          val ssym = desig.asInstanceOf[Symbol].symbolName
          s.ThisType(ssym)

        case SuperType(thistpe, supertpe) =>
          val spre = loop(thistpe.typeSymbol.info)
          val ssym = supertpe.typeSymbol.symbolName
          s.SuperType(spre, ssym)

        // val clazzOf = classOf[...]
        case ConstantType(const) if const.tag == core.Constants.ClazzTag =>
          loop(const.typeValue)

        case ConstantType(const) =>
          s.ConstantType(const.toSemanticConst)

        case lam: HKTypeLambda =>
          val stparams = lam.paramNames.flatMap { paramName =>
            val key = (lam, paramName)
            val got = paramRefSymtab.get(key)
            got
          }.sscope
          val sresTpe = loop(lam.resType)
          val res = s.TypeLambda(Some(stparams), sresTpe)
          res

        case matchType: MatchType =>
          val scases = matchType.cases.map { caseType => caseType match {
            case lam: HKTypeLambda =>
              // val paramSyms = lam.paramNames.flatMap { paramName =>
              //   val key = (lam, paramName)
              //   val get = paramRefSymtab.get(key)
              //   get
              // }.sscope
              lam.resType match {
                case defn.MatchCase(key, body) =>
                  pprint.pprintln(key)
                  pprint.pprintln(body)
                  s.MatchType.CaseType(
                    loop(key),
                    loop(body)
                  )
                case _ => s.MatchType.CaseType() // shouldn't happen
              }
            case defn.MatchCase(key, body) =>
              val skey = loop(key)
              val sbody = loop(body)
              s.MatchType.CaseType(skey, sbody)
            case _ => s.MatchType.CaseType() // shouldn't happen
          }}
          val sscrutinee = loop(matchType.scrutinee)
          val sbound = loop(matchType.bound)
          val t = s.MatchType(sscrutinee, sbound, scases)
          println(s"===${sym}===")
          pprint.pprintln(matchType)
          pprint.pprintln(t)
          t

        case rt @ RefinedType(parent, name, info) =>
          // `X { def x: Int; def y: Int }`
          // RefinedType(
          //   parent = RefinedType(
          //     parent = TypeRef(..., X)
          //     ...
          //   )
          //   refinedName = x
          //   refinedInfo = TypeRef(..., Int)
          // )
          type RefinedInfo = (core.Names.Name, Type)
          def flatten(tpe: Type, acc: List[RefinedInfo]): (Type, List[RefinedInfo]) = tpe match {
            case RefinedType(parent, name, info) =>
              flatten(parent, acc :+ (name, info))
            case _ =>
              (tpe, acc)
          }

          // flatten parent types to list
          // e.g. `X with Y with Z { refined }`
          // RefinedType(parent = AndType(X, AndType(Y, Z)), ...)
          // => List(X, Y, Z)
          def flattenParent(parent: Type): List[s.Type] = parent match {
            case AndType(tp1, tp2) =>
              flattenParent(tp1) ++ flattenParent(tp2)
            case _ => List(loop(parent))
          }

          val (parent, refinedInfos) = flatten(rt, List.empty)
          val stpe = s.WithType(flattenParent(parent))

          val decls = refinedInfos.flatMap { (name, _) =>
            refinementSymtab.get((rt, name))
          }
          val sdecls = decls.sscope(using LinkMode.HardlinkChildren)
          s.StructuralType(stpe, Some(sdecls))

        case rec: RecType =>
          loop(rec.parent) // should be handled as RefinedType

        // repeated params: e.g. `Int*`, which is the syntax sugar of
        // `Seq[Int] @Repeated` (or `Array[Int] @Repeated`)
        // See: Desugar.scala and TypeApplications.scala
        case AnnotatedType(AppliedType(_, targs), annot)
          if (annot matches defn.RepeatedAnnot) && (targs.length == 1) =>
          val stpe = loop(targs(0))
          s.RepeatedType(stpe)

        case ann: AnnotatedType if ann.annot.symbol.info.isInstanceOf[ClassInfo] =>
          def flatten(tpe: Type, annots: List[Annotation]): (Type, List[Annotation]) = tpe match
            case AnnotatedType(parent, annot) if annot.symbol.info.isInstanceOf[ClassInfo] =>
              flatten(parent, annot +: annots)
            case other => (other, annots)

          val (parent, annots) = flatten(ann, List.empty)
          val sparent = loop(parent)
          val sannots = annots.map(a =>
            s.Annotation(loop(a.symbol.info.asInstanceOf[ClassInfo].selfType))
          )
          s.AnnotatedType(sannots, sparent)

        case app @ AppliedType(tycon, args) =>
          val sargs = args.map(loop)
          loop(tycon) match
            case ref: s.TypeRef => ref.copy(typeArguments = sargs)
            // is there any other cases?
            case _ => s.Type.Empty

        case and: AndType =>
          def flatten(child: Type): List[Type] = child match
            case AndType(ct1, ct2) => flatten(ct1) ++ flatten(ct2)
            case other => List(other)
          val stpes = flatten(and).map(loop)
          s.IntersectionType(stpes)

        case or: OrType =>
          def flatten(child: Type): List[Type] = child match
            case OrType(ct1, ct2) => flatten(ct1) ++ flatten(ct2)
            case other => List(other)
          val stpes = flatten(or).map(loop)
          s.UnionType(stpes)

        case NoPrefix =>
          s.Type.Empty

        case _ => s.Type.Empty
      }
      loop(tpe)

    /** Return true if the prefix is like `_root_.this` */
    private def hasTrivialPrefix(using Context): Boolean =
      def checkTrivialPrefix(pre: Type, sym: Symbol)(using Context): Boolean =
        pre =:= sym.owner.thisType
      tpe match {
        case TypeRef(pre, desig) if desig.isInstanceOf[Symbol] =>
          checkTrivialPrefix(pre, desig.asInstanceOf[Symbol])
        case TermRef(pre, desig) if desig.isInstanceOf[Symbol] =>
          checkTrivialPrefix(pre, desig.asInstanceOf[Symbol])
        case _ => false
      }

object SymbolScopeOps:
  import Scala3.given
  extension (syms: List[Symbol])
    def sscope(using linkMode: LinkMode)(using SemanticSymbolBuilder, TypeOps, Context): s.Scope =
      linkMode match {
        case LinkMode.SymlinkChildren =>
          s.Scope(symlinks = syms.map(_.symbolName))
        case LinkMode.HardlinkChildren =>
          s.Scope(hardlinks = syms.map(_.symbolInfo(Set.empty)))
      }
