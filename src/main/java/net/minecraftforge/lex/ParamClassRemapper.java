package net.minecraftforge.lex;

import java.util.function.BiFunction;

import org.cadixdev.bombe.analysis.InheritanceProvider;
import org.cadixdev.bombe.type.signature.MethodSignature;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.Mapping;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

public class ParamClassRemapper extends ClassRemapper {
    public static BiFunction<ClassVisitor, Remapper, ClassRemapper> create(final MappingSet mappings, final InheritanceProvider inheritanceProvider) {
        return (classVisitor, remapper) -> new ParamClassRemapper(classVisitor, remapper, mappings, inheritanceProvider);
    }

    private final MappingSet mappings;
    private final InheritanceProvider inheritanceProvider;
    public ParamClassRemapper(ClassVisitor classVisitor, Remapper remapper, MappingSet mappings, InheritanceProvider inheritanceProvider) {
        super(classVisitor, remapper);
        this.mappings = mappings;
        this.inheritanceProvider = inheritanceProvider;
    }

    @Override
    public MethodVisitor visitMethod(final int access, final String mname, final String mdescriptor, final String msignature, final String[] exceptions) {
        String remappedDescriptor = remapper.mapMethodDesc(mdescriptor);
        MethodVisitor methodVisitor = super.visitMethod(access, remapper.mapMethodName(className, mname, mdescriptor), remappedDescriptor, remapper.mapSignature(msignature, false), exceptions == null ? null : remapper.mapTypes(exceptions));
        return methodVisitor == null ? null :
            new MethodVisitor(api, createMethodRemapper(methodVisitor)) {
                @Override
                public void visitLocalVariable(final String pname, final String pdescriptor, final String psignature, final Label start, final Label end, final int index) {
                    super.visitLocalVariable(mapParameterName(className, mname, mdescriptor, index), pdescriptor, psignature, start, end, index);
                }
            };
    }

    private ClassMapping<?, ?> getCompletedClassMapping(final String owner) {
        final ClassMapping<?, ?> mapping = this.mappings.getOrCreateClassMapping(owner);
        mapping.complete(this.inheritanceProvider);
        return mapping;
    }

    public String mapParameterName(final String owner, final String name, final String desc, final int index) {
        return this.getCompletedClassMapping(owner)
                .getMethodMapping(MethodSignature.of(name, desc))
                .flatMap(m -> m.getParameterMapping(index))
                .map(Mapping::getDeobfuscatedName)
                .orElse(name);
    }
}
