package org.cadixdev.mercury.remapper;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.MemberMapping;
import org.cadixdev.lorenz.model.MethodMapping;
import org.cadixdev.mercury.RewriteContext;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;

import java.util.*;
import java.util.function.BiFunction;

import static org.cadixdev.mercury.util.BombeBindings.convertSignature;

// I stole this from
// https://github.com/dimitriye98/filigree/blob/f7530776bff4eaedc259d317a06ef5684a8f4a56/src/main/java/com/dimitriye/filigree/remapper/SimpleRemapperVisitor.java#L66
public final class GenericParametersHotfix {

    public static MethodMapping findGenericMapping(IMethodBinding binding, ITypeBinding declaringClass, RewriteContext context, MappingSet mappings, MemberMappingLocator locator) {
        List<ITypeBinding> parents = new ArrayList<>();
        ascendHierarchy(declaringClass, parents, context.createASTRewrite().getAST());

        for (ITypeBinding parent : parents) {
            final ClassMapping<?, ?> parentMapping = mappings.getClassMapping(parent.getBinaryName()).orElse(null);
            if (parentMapping == null) {
                continue;
            }
            IMethodBinding[] methods = parent.getDeclaredMethods();
            for (int i = 0; i < methods.length; ++i) {
                IMethodBinding method = methods[i];
                if (binding.overrides(method)) {
                    IMethodBinding canonical = parent.getErasure().getDeclaredMethods()[i];

                    return locator.findMemberMapping(
                        convertSignature(canonical.getMethodDeclaration()),
                        parentMapping,
                        ClassMapping::getMethodMapping
                    );

                }
            }
        }

        return null;
    }

    public interface MemberMappingLocator {
        <T extends MemberMapping<?, ?>, M> T findMemberMapping(M matcher, ClassMapping<?, ?> classMapping, BiFunction<ClassMapping<?, ?>, M, Optional<? extends T>> getMapping);
    }

    private static void ascendHierarchy(ITypeBinding binding, Collection<ITypeBinding> col, AST ast) {
        if (Objects.equals(binding, ast.resolveWellKnownType("java.lang.Object"))) {
            return;
        }

        if (!binding.isInterface()) {
            col.add(binding.getSuperclass());
            ascendHierarchy(binding.getSuperclass(), col, ast);
        }

        for (ITypeBinding it : binding.getInterfaces()) {
            col.add(it);
            ascendHierarchy(it, col, ast);
        }
    }
}
