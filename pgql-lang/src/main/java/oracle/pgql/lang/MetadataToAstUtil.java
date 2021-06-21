package oracle.pgql.lang;

import static oracle.pgql.lang.CommonTranslationUtil.getString;
import static oracle.pgql.lang.CommonTranslationUtil.isSome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.metaborg.spoofax.core.unit.ISpoofaxAnalyzeUnit;
import org.metaborg.spoofax.core.unit.ISpoofaxParseUnit;
import org.spoofax.interpreter.terms.IStrategoAppl;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.interpreter.terms.ITermFactory;
import org.spoofax.interpreter.terms.TermType;
import org.spoofax.terms.TermVisitor;

import oracle.pgql.lang.ir.SchemaQualifiedName;
import oracle.pgql.lang.metadata.AbstractMetadataProvider;
import oracle.pgql.lang.metadata.EdgeLabel;
import oracle.pgql.lang.metadata.GraphSchema;
import oracle.pgql.lang.metadata.Label;
import oracle.pgql.lang.metadata.Property;
import oracle.pgql.lang.metadata.VertexLabel;

public class MetadataToAstUtil {

  private static final int POS_AST_PLUS_METADATA_AST_EXPRESSIONS = 0;

  private static final String AST_PLUS_METADATA_CONSTRUCTOR_NAME = "AstPlusMetadata";

  static ISpoofaxParseUnit addMetadata(ISpoofaxParseUnit parseResult, AbstractMetadataProvider metadataProvider,
      ITermFactory f) {
    if (!((IStrategoAppl) parseResult.ast()).getConstructor().getName().equals("Query")) {
      // for DDL statements and other non-query statement, we don't add metadata
      return parseResult;
    }

    if (metadataProvider == null) {
      return parseResult;
    }

    Set<SchemaQualifiedName> graphNames = extractGraphNames(parseResult.ast());
    Optional<GraphSchema> graphSchema;
    if (graphNames.size() > 1) {
      // multiple graph references in single query are currently not supported
      // we already generate an error for that during analysis so we can just return here
      return parseResult;
    } else if (graphNames.size() == 1) {
      SchemaQualifiedName graphName = graphNames.iterator().next();
      graphSchema = metadataProvider.getGraphSchema(graphName);
    } else {
      graphSchema = metadataProvider.getGraphSchema();
    }

    Set<String> allTypes = new HashSet<>();

    List<IStrategoTerm> metadataTerm = new ArrayList<>();
    if (graphSchema.isPresent()) {

      List<IStrategoTerm> vertexLabelTerms = new ArrayList<>();
      for (VertexLabel vertexLabel : graphSchema.get().getVertexLabels()) {
        vertexLabelTerms.add(translateLabel(vertexLabel, f, allTypes));
      }
      IStrategoAppl vertexLabelsTerm = f.makeAppl("VertexLabels", f.makeList(vertexLabelTerms));
      metadataTerm.add(vertexLabelsTerm);

      List<IStrategoTerm> edgeLabelTerms = new ArrayList<>();
      for (EdgeLabel edgeLabel : graphSchema.get().getEdgeLabels()) {
        edgeLabelTerms.add(translateLabel(edgeLabel, f, allTypes));
      }
      IStrategoAppl edgeLabelsTerm = f.makeAppl("EdgeLabels", f.makeList(edgeLabelTerms));
      metadataTerm.add(edgeLabelsTerm);
    }

    if (metadataProvider.getDefaultStringType().isPresent()) {
      String type = metadataProvider.getDefaultStringType().get();
      IStrategoAppl defaultStringType = f.makeAppl("DefaultStringType", f.makeString(type));
      metadataTerm.add(defaultStringType);
      allTypes.add(type);
    }

    if (metadataProvider.getDefaultShortIntegerType().isPresent()) {
      String type = metadataProvider.getDefaultShortIntegerType().get();
      IStrategoAppl defaultShortIntegerType = f.makeAppl("DefaultShortIntegerType", f.makeString(type));
      metadataTerm.add(defaultShortIntegerType);
      allTypes.add(type);
    }

    if (metadataProvider.getDefaultLongIntegerType().isPresent()) {
      String type = metadataProvider.getDefaultLongIntegerType().get();
      IStrategoAppl defaultLongIntegerType = f.makeAppl("DefaultLongIntegerType", f.makeString(type));
      metadataTerm.add(defaultLongIntegerType);
      allTypes.add(type);
    }

    if (metadataProvider.getDefaultDecimalType().isPresent()) {
      String type = metadataProvider.getDefaultDecimalType().get();
      IStrategoAppl defaultDecimalType = f.makeAppl("DefaultDecimalType", f.makeString(type));
      metadataTerm.add(defaultDecimalType);
      allTypes.add(type);
    }

    allTypes.add("BOOLEAN");
    allTypes.add("DATE");
    allTypes.add("TIME");
    allTypes.add("TIME WITH TIME ZONE");
    allTypes.add("TIMESTAMP");
    allTypes.add("TIMESTAMP WITH TIME ZONE");

    List<Pair<String, String>> allPairsOfTypes = getAllPairsOfTypes(allTypes);
    IStrategoTerm unionCompatibleTypes = getUnionCompatibleTypes(allPairsOfTypes, metadataProvider, f);
    metadataTerm.add(unionCompatibleTypes);

    IStrategoAppl metadataExtendedAst = f.makeAppl(AST_PLUS_METADATA_CONSTRUCTOR_NAME, parseResult.ast(),
        f.makeList(metadataTerm));
    System.out.println(metadataTerm);
    ISpoofaxParseUnit extendedParseUnit = new ModifiedParseUnit(parseResult, metadataExtendedAst);
    return extendedParseUnit;
  }

  static IStrategoTerm translateLabel(Label label, ITermFactory f, Set<String> allTypes) {
    List<IStrategoTerm> propertyTerms = new ArrayList<>();
    for (Property property : label.getProperties()) {
      propertyTerms.add(f.makeAppl("Property", f.makeString(property.getName()), f.makeString(property.getType())));
      allTypes.add(property.getType());
    }

    return f.makeAppl("Label", f.makeString(label.getLabel()), f.makeList(propertyTerms));
  }

  static IStrategoTerm removeMetadata(ISpoofaxAnalyzeUnit analysisResult) {
    IStrategoTerm analyizedAst;
    if (((IStrategoAppl) analysisResult.ast()).getConstructor().getName().equals(AST_PLUS_METADATA_CONSTRUCTOR_NAME)) {
      analyizedAst = analysisResult.ast().getSubterm(POS_AST_PLUS_METADATA_AST_EXPRESSIONS);
    } else {
      analyizedAst = analysisResult.ast();
    }
    return analyizedAst;
  }

  static Set<SchemaQualifiedName> extractGraphNames(IStrategoTerm ast) {

    final Set<SchemaQualifiedName> graphNames = new HashSet<>();

    new TermVisitor() {

      @Override
      public void preVisit(IStrategoTerm t) {
        if (t.getType() == TermType.APPL) {
          String constructor = ((IStrategoAppl) t).getConstructor().getName();
          if (constructor.equals("OnClause") || constructor.equals("IntoClause")) {
            IStrategoTerm nameT = t.getSubterm(0);
            IStrategoTerm schemaNameT = nameT.getSubterm(0);
            String schemaName = isSome(schemaNameT) ? identifierToString(schemaNameT.getSubterm(0).getSubterm(0))
                : null;
            String localName = identifierToString(nameT.getSubterm(1));
            graphNames.add(new SchemaQualifiedName(schemaName, localName));
          }
        }
      }

    }.visit(ast);

    return graphNames;
  }

  static String identifierToString(IStrategoTerm t) {
    String constructorName = ((IStrategoAppl) t).getConstructor().getName();
    String identifier = getString(t);
    switch (constructorName) {
      case "RegularIdentifier":
        return identifier.toUpperCase();
      case "DelimitedIdentifier":
        return identifier.substring(1, identifier.length() - 1).replaceAll("\"\"", "\"");
      default:
        throw new IllegalStateException("Unsupported identifier type: " + constructorName);
    }
  }

  private static List<Pair<String, String>> getAllPairsOfTypes(Set<String> allTypes) {
    List<Pair<String, String>> result = new ArrayList<>();
    for (String type1 : allTypes) {
      for (String type2 : allTypes) {
        if (!type1.equals(type2)) {
          result.add(Pair.of(type1, type2));
        }
      }
    }
    return result;
  }

  private static IStrategoTerm getUnionCompatibleTypes(List<Pair<String, String>> allPairsOfTypes,
      AbstractMetadataProvider metadataProvider, ITermFactory f) {
    List<IStrategoTerm> unionTypes = new ArrayList<>();
    for (Pair<String, String> pair : allPairsOfTypes) {
      Optional<String> optionalUnionType = metadataProvider.getUnionType(pair.getLeft(), pair.getRight());
      if (optionalUnionType == null) {
        unionTypes.add(f.makeAppl("UnionType", f.makeString(pair.getLeft()), f.makeString(pair.getRight()),
            f.makeAppl("Undefined")));
      } else if (optionalUnionType.isPresent()) {
        String unionType = optionalUnionType.get();
        if (!unionType.isEmpty()) {
          unionTypes.add(f.makeAppl("UnionType", f.makeString(pair.getLeft()), f.makeString(pair.getRight()),
              f.makeString(unionType)));
        }

      }
    }
    return f.makeAppl("UnionTypes", f.makeList(unionTypes));
  }
}
