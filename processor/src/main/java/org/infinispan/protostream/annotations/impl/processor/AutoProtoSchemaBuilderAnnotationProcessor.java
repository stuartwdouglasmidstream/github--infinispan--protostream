package org.infinispan.protostream.annotations.impl.processor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Generated;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import org.infinispan.protostream.DescriptorParserException;
import org.infinispan.protostream.GeneratedSchema;
import org.infinispan.protostream.ProtobufUtil;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.protostream.SerializationContextInitializer;
import org.infinispan.protostream.Version;
import org.infinispan.protostream.WrappedMessage;
import org.infinispan.protostream.annotations.AutoProtoSchemaBuilder;
import org.infinispan.protostream.annotations.ProtoSchemaBuilderException;
import org.infinispan.protostream.annotations.impl.IndentWriter;
import org.infinispan.protostream.annotations.impl.processor.types.HasModelElement;
import org.infinispan.protostream.annotations.impl.processor.types.MirrorTypeFactory;
import org.infinispan.protostream.annotations.impl.types.XClass;
import org.infinispan.protostream.annotations.impl.types.XMethod;

import com.google.auto.service.AutoService;

@SupportedOptions({AutoProtoSchemaBuilderAnnotationProcessor.DEBUG_OPTION, AutoProtoSchemaBuilderAnnotationProcessor.FULLY_QUALIFIED_ANNOTATIONS})
@SupportedAnnotationTypes(AutoProtoSchemaBuilderAnnotationProcessor.ANNOTATION_NAME)
@AutoService(Processor.class)
public final class AutoProtoSchemaBuilderAnnotationProcessor extends AbstractProcessor {

   /**
    * Activate debug logging.
    */
   public static final String DEBUG_OPTION = "protostream.debug";

   public static final String FULLY_QUALIFIED_ANNOTATIONS = "protostream.fullyqualifiedannotations";

   /**
    * The FQN of the one and only annotation we claim.
    */
   static final String ANNOTATION_NAME = "org.infinispan.protostream.annotations.AutoProtoSchemaBuilder";

   private final ServiceLoaderFileGenerator serviceLoaderFileGenerator = new ServiceLoaderFileGenerator(SerializationContextInitializer.class);

   private GeneratedFilesWriter generatedFilesWriter;

   private boolean isDebugEnabled;

   private MirrorTypeFactory typeFactory;

   /**
    * Keep track of what we process so we do not get into a dependency loop and cause a stack overflow.
    */
   private final Set<String> processedElementsFQN = new HashSet<>();

   private Types types;

   private Elements elements;

   private Filer filer;

   private Messager messager;

   @Override
   public synchronized void init(ProcessingEnvironment processingEnv) {
      super.init(processingEnv);

      isDebugEnabled = Boolean.parseBoolean(processingEnv.getOptions().get(DEBUG_OPTION));
      typeFactory = new MirrorTypeFactory(processingEnv);
      types = processingEnv.getTypeUtils();
      elements = processingEnv.getElementUtils();
      filer = processingEnv.getFiler();
      messager = processingEnv.getMessager();

      generatedFilesWriter = new GeneratedFilesWriter(filer);

      ensureRequiredEnv();
   }

   @Override
   public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
   }

   /**
    * Issue a compilation error.
    */
   private void reportError(AnnotationProcessingException ex) {
      if (ex.getLocation() != null) {
         messager.printMessage(Diagnostic.Kind.ERROR, ex.getFormattedMessage(), ex.getLocation());
      } else {
         messager.printMessage(Diagnostic.Kind.ERROR, ex.getFormattedMessage());
      }
   }

   /**
    * Issue a compilation error.
    */
   private void reportError(Element e, String message, Object... msgParams) {
      String formatted = String.format(message, msgParams);
      if (e != null) {
         messager.printMessage(Diagnostic.Kind.ERROR, formatted, e);
      } else {
         messager.printMessage(Diagnostic.Kind.ERROR, formatted);
      }
   }

   /**
    * Issue a compilation warning.
    */
   private void reportWarning(Element e, String message, Object... msgParams) {
      String formatted = String.format(message, msgParams);
      if (e != null) {
         messager.printMessage(Diagnostic.Kind.WARNING, formatted, e);
      } else {
         messager.printMessage(Diagnostic.Kind.WARNING, formatted);
      }
   }

   /**
    * Log a debug message, only if debug option is enabled.
    */
   private void logDebug(String message, Object... msgParams) {
      if (isDebugEnabled) {
         messager.printMessage(Diagnostic.Kind.NOTE, String.format(message, msgParams));
      }
   }

   private AutoProtoSchemaBuilder getBuilderAnnotation(Element annotatedElement) {
      try {
         return annotatedElement.getAnnotation(AutoProtoSchemaBuilder.class);
      } catch (ClassCastException e) {
         // javac soiling pants
         reportError(annotatedElement, "Some of the classes referenced by the AutoProtoSchemaBuilder annotation " +
               "do not exist, possibly due to compilation errors in your source code or due to " +
               "incremental compilation issues caused by your build system. Please try a clean rebuild.");
      }
      return null;
   }

   //todo [anistor] check RoundEnvironment.errorRaised() and do not write any more output files if errors are present
   @Override
   public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (isDebugEnabled) {
         logDebug("AutoProtoSchemaBuilderAnnotationProcessor annotations=%s, rootElements=%s", annotations, roundEnv.getRootElements());
      }

      Optional<? extends TypeElement> claimedAnnotation = annotations.stream()
            .filter(a -> a.getQualifiedName().contentEquals(ANNOTATION_NAME))
            .findAny();

      try {
         if (claimedAnnotation.isPresent()) {
            for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(claimedAnnotation.get())) {
               AutoProtoSchemaBuilder builderAnnotation = getBuilderAnnotation(annotatedElement);
               SerializationContext serCtx = ProtobufUtil.newSerializationContext();
               try {
                  processElement(roundEnv, serCtx, annotatedElement, builderAnnotation, new ProcessorContext());
               } catch (ProtoSchemaBuilderException | DescriptorParserException e) {
                  throw new AnnotationProcessingException(e, annotatedElement, "%s", getStackTraceAsString(e));
               }
            }
         }

         if (roundEnv.processingOver()) {
            serviceLoaderFileGenerator.writeServiceFile(filer);
         }
      } catch (AnnotationProcessingException e) {
         // this is caused by the user supplying incorrect data in the annotation or related classes
         if (isDebugEnabled) {
            logDebug("@AutoProtoSchemaBuilder processor threw an exception: %s", getStackTraceAsString(e));
         }
         reportError(e);
      } catch (Exception e) {
         // this may be a fatal programming error in the annotation processor itself
         reportError(null, "@AutoProtoSchemaBuilder processor threw a fatal exception: %s", getStackTraceAsString(e));
      }

      return claimedAnnotation.isPresent();
   }

   private void ensureRequiredEnv() {
      if (getJavaMajorVersion() < 9 && !Boolean.getBoolean("org.infinispan.protostream.skipAnnotationCompilerCheck")) {
         reportWarning(null, "Please ensure you use at least Java ver. 9 for compilation in order to avoid various " +
               "compiler related bugs from older Java versions that impact the AutoProtoSchemaBuilder annotation " +
               "processor (you can still set the output target to 8 or above). To hide this warning set the " +
               "`org.infinispan.protostream.skipAnnotationCompilerCheck` system property");
      }

      Version procVersion = Version.getVersion(AutoProtoSchemaBuilder.class);
      if (Version.getVersion().compareTo(procVersion) != 0) {
         // protostream core and processor versions must be identical to ensure compatibility
         reportWarning(null, "Version mismatch! protostream (%s) and protostream-processor (%s) are expected to be the same version. " +
               "Mixing different versions is unsupported and can lead to unintended consequences.", Version.getVersion(), procVersion);
      }
   }

   private static int getJavaMajorVersion() {
      String[] version = System.getProperty("java.version").split("[.]");
      int major = parseVersionPart(version[0]);
      if (major == 1) {
         major = parseVersionPart(version[1]);
      }
      return major;
   }

   private static int parseVersionPart(String s) {
      Matcher m = Pattern.compile("(\\d+)\\D*").matcher(s);
      return m.find() ? Integer.parseInt(m.group(1)) : 0;
   }

   private static String getStackTraceAsString(Throwable throwable) {
      StringWriter stringWriter = new StringWriter();
      throwable.printStackTrace(new PrintWriter(stringWriter));
      return stringWriter.toString();
   }

   private void processElement(RoundEnvironment roundEnv, SerializationContext serCtx, Element annotatedElement,
                               AutoProtoSchemaBuilder annotation, ProcessorContext processorContext) throws IOException {
      if (annotatedElement.getKind() != ElementKind.PACKAGE && annotatedElement.getKind() != ElementKind.INTERFACE && annotatedElement.getKind() != ElementKind.CLASS) {
         throw new AnnotationProcessingException(annotatedElement, "@AutoProtoSchemaBuilder annotation can only be applied to classes, interfaces and packages.");
      }

      AnnotatedClassScanner classScanner = new AnnotatedClassScanner(messager, elements, types, typeFactory, annotatedElement, annotation);
      classScanner.discoverClasses(roundEnv);
      logDebug("AnnotatedClassScanner.discoverClasses returned: %s", classScanner.getClasses());

      if (classScanner.getClasses().isEmpty()) {
         reportWarning(annotatedElement, "No ProtoStream annotated classes found matching the criteria. Please review the 'includeClasses' / 'basePackages' attribute of the @AutoProtoSchemaBuilder annotation.");
      }

      if (annotatedElement.getKind() == ElementKind.PACKAGE) {
         processPackage(roundEnv, serCtx, (PackageElement) annotatedElement, annotation, classScanner, processorContext);
      } else {
         processClass(roundEnv, serCtx, (TypeElement) annotatedElement, annotation, classScanner, processorContext);
      }
   }

   private void processPackage(RoundEnvironment roundEnv, SerializationContext serCtx, PackageElement packageElement, AutoProtoSchemaBuilder builderAnnotation,
                               AnnotatedClassScanner classScanner, ProcessorContext processorContext) throws IOException {
      String initializerClassName = builderAnnotation.className();
      if (initializerClassName.isEmpty()) {
         throw new AnnotationProcessingException(packageElement, "@AutoProtoSchemaBuilder.className is required when annotating a package.");
      }
      if (!SourceVersion.isIdentifier(initializerClassName) || SourceVersion.isKeyword(initializerClassName)) {
         throw new AnnotationProcessingException(packageElement, "@AutoProtoSchemaBuilder.className annotation attribute must be a valid Java identifier and must not be fully qualified.");
      }

      String initializerPackageName = packageElement.isUnnamed() ? null : packageElement.getQualifiedName().toString();
      String initializerFQN = initializerPackageName != null ? initializerPackageName + '.' + initializerClassName : initializerClassName;
      String protobufPackageName = builderAnnotation.schemaPackageName().isEmpty() ? null : builderAnnotation.schemaPackageName();
      String protobufFileName = getProtobufFileName(packageElement, builderAnnotation);

      ProcessorContext dependencies = processDependencies(roundEnv, serCtx, packageElement, builderAnnotation);

      Set<XClass> xclasses = classScanner.getXClasses();

      CompileTimeProtoSchemaGenerator protoSchemaGenerator = new CompileTimeProtoSchemaGenerator(typeFactory, generatedFilesWriter, serCtx,
            initializerPackageName, protobufFileName, protobufPackageName, dependencies.marshalledClasses, xclasses, builderAnnotation.autoImportClasses(), classScanner);
      String schemaSrc = protoSchemaGenerator.generateAndRegister();

      writeSerializationContextInitializer(packageElement, packageElement.getQualifiedName().toString(), builderAnnotation,
            dependencies.initializerClassNames, classScanner.getClasses(), protoSchemaGenerator.getGeneratedMarshallerClasses(),
            initializerPackageName, initializerClassName, initializerFQN,
            protobufFileName, protobufPackageName, schemaSrc);

      processorContext.add(classScanner.getInitializerFQClassName(), protobufFileName, protoSchemaGenerator.getMarshalledClasses());
   }

   private void processClass(RoundEnvironment roundEnv, SerializationContext serCtx, TypeElement typeElement, AutoProtoSchemaBuilder builderAnnotation,
                             AnnotatedClassScanner classScanner, ProcessorContext processorContext) throws IOException {
      if (typeElement.getNestingKind() == NestingKind.LOCAL || typeElement.getNestingKind() == NestingKind.ANONYMOUS) {
         throw new AnnotationProcessingException(typeElement, "Classes or interfaces annotated with @AutoProtoSchemaBuilder must not be local or anonymous.");
      }
      if (typeElement.getNestingKind() == NestingKind.MEMBER && !typeElement.getModifiers().contains(Modifier.STATIC)) {
         throw new AnnotationProcessingException(typeElement, "Nested classes or interfaces annotated with @AutoProtoSchemaBuilder must be static.");
      }
      if (typeElement.getModifiers().contains(Modifier.FINAL)) {
         throw new AnnotationProcessingException(typeElement, "Classes annotated with @AutoProtoSchemaBuilder must not be final.");
      }
      if (!builderAnnotation.className().isEmpty() && (!SourceVersion.isIdentifier(builderAnnotation.className()) || SourceVersion.isKeyword(builderAnnotation.className()))) {
         throw new AnnotationProcessingException(typeElement, "@AutoProtoSchemaBuilder.className annotation attribute must be a valid Java identifier and must not be fully qualified.");
      }
      TypeMirror serializationContextInitializerTypeMirror = elements.getTypeElement(SerializationContextInitializer.class.getName()).asType();
      if (!types.isSubtype(typeElement.asType(), serializationContextInitializerTypeMirror)) {
         throw new AnnotationProcessingException(typeElement, "Classes or interfaces annotated with @AutoProtoSchemaBuilder must implement/extend %s", SerializationContextInitializer.class.getName());
      }

      PackageElement packageElement = elements.getPackageOf(typeElement);
      String initializerPackageName = packageElement.isUnnamed() ? null : packageElement.getQualifiedName().toString();
      String initializerClassName = classScanner.getInitializerClassName();
      String initializerFQN = classScanner.getInitializerFQClassName();
      String protobufPackageName = builderAnnotation.schemaPackageName().isEmpty() ? null : builderAnnotation.schemaPackageName();
      String protobufFileName = getProtobufFileName(typeElement, builderAnnotation);

      ProcessorContext dependencies = processDependencies(roundEnv, serCtx, typeElement, builderAnnotation);

      warnOverrideExistingMethods(typeElement);

      Set<XClass> xclasses = classScanner.getXClasses();

      CompileTimeProtoSchemaGenerator protoSchemaGenerator = new CompileTimeProtoSchemaGenerator(typeFactory, generatedFilesWriter, serCtx,
            typeElement.getQualifiedName().toString(), protobufFileName, protobufPackageName, dependencies.marshalledClasses, xclasses, builderAnnotation.autoImportClasses(), classScanner);

      String schemaSrc = protoSchemaGenerator.generateAndRegister();

      writeSerializationContextInitializer(typeElement, typeElement.getQualifiedName().toString(), builderAnnotation,
            dependencies.initializerClassNames, classScanner.getClasses(), protoSchemaGenerator.getGeneratedMarshallerClasses(),
            initializerPackageName, initializerClassName, initializerFQN,
            protobufFileName, protobufPackageName, schemaSrc);

      processorContext.add(classScanner.getInitializerFQClassName(), protobufFileName, protoSchemaGenerator.getMarshalledClasses());
   }

   private String getProtobufFileName(Element element, AutoProtoSchemaBuilder annotation) {
      if (annotation.schemaFileName().isEmpty()) {
         return element.getSimpleName() + ".proto";
      }
      if (!annotation.schemaFileName().endsWith(".proto")) {
         reportWarning(element, "@AutoProtoSchemaBuilder.schemaFileName should end with '.proto' : %s", annotation.schemaFileName());
      }
      return annotation.schemaFileName();
   }

   private static final class ProcessorContext {

      /**
       * Names of SerializationContextInitializer generated so far in this context.
       */
      final Set<String> initializerClassNames = new LinkedHashSet<>();

      /**
       * The key is a marshalled class, the value is the name of the protobuf file that contains it.
       */
      final Map<XClass, String> marshalledClasses = new HashMap<>();

      void add(String initializerFQN, String protobufFileName, Set<XClass> classes) {
         initializerClassNames.add(initializerFQN);
         for (XClass c : classes) {
            marshalledClasses.put(c, protobufFileName);
         }
      }
   }

   //todo [anistor] we do not support yet dependencies on packages, only on types
   private ProcessorContext processDependencies(RoundEnvironment roundEnv, SerializationContext serCtx,
                                                Element annotatedElement, AutoProtoSchemaBuilder builderAnnotation) throws IOException {
      List<? extends TypeMirror> dependencies = DangerousActions.getTypeMirrors(builderAnnotation, AutoProtoSchemaBuilder::dependsOn);

      ProcessorContext processorContext = new ProcessorContext();

      // register internal known types
      processorContext.marshalledClasses.put(typeFactory.fromClass(WrappedMessage.class), WrappedMessage.PROTO_FILE);

      for (TypeMirror dependencyType : dependencies) {
         TypeElement dependencyElement = (TypeElement) types.asElement(dependencyType);
         String dependencyFQN = dependencyElement.getQualifiedName().toString();
         AutoProtoSchemaBuilder dependencyAnnotation = getBuilderAnnotation(dependencyElement);
         if (dependencyAnnotation == null) {
            throw new AnnotationProcessingException(annotatedElement, "Dependency %s is not annotated with @AutoProtoSchemaBuilder annotation", dependencyFQN);
         }

         // now we (re)process the dependency in order to gather all metadata again and also to detect eventual cycles!

         if (!processedElementsFQN.add(dependencyFQN)) {
            throw new AnnotationProcessingException(annotatedElement, "Illegal recursive dependency on %s", dependencyFQN);
         }

         // disable source file emission for dependencies because they are already compiled
         boolean wasGenerationEnabled = generatedFilesWriter.isEnabled();
         generatedFilesWriter.setEnabled(false);

         processElement(roundEnv, serCtx, dependencyElement, dependencyAnnotation, processorContext);

         // restore previous state of source file emission
         generatedFilesWriter.setEnabled(wasGenerationEnabled);

         processedElementsFQN.remove(dependencyFQN);
      }

      return processorContext;
   }

   private void warnOverrideExistingMethods(TypeElement typeElement) {
      XClass annotatedType = typeFactory.fromTypeMirror(typeElement.asType());
      warnOverrideExistingMethod(annotatedType, "getProtoFileName");
      warnOverrideExistingMethod(annotatedType, "getProtoFile");
      XClass serializationContextClass = typeFactory.fromClass(SerializationContext.class);
      warnOverrideExistingMethod(annotatedType, "registerSchema", serializationContextClass);
      warnOverrideExistingMethod(annotatedType, "registerMarshallers", serializationContextClass);
   }

   private void warnOverrideExistingMethod(XClass xclass, String methodName, XClass... argTypes) {
      XMethod method = xclass.getMethod(methodName, argTypes);
      if (method != null && !method.isAbstract()) {
         reportWarning(((HasModelElement) method).getElement(), "Code generated by @AutoProtoSchemaBuilder processor will override your %s.%s method.",
               method.getDeclaringClass().getName(), method.getName());
      }
   }

   private void writeSerializationContextInitializer(Element annotatedElement, String annotatedElementFQN, AutoProtoSchemaBuilder annotation,
                                                     Set<String> serCtxInitDeps,
                                                     Collection<? extends TypeMirror> classes, Set<String> generatedMarshallerClasses,
                                                     String packageName, String initializerClassName, String initializerFQN,
                                                     String fileName, String protobufPackageName, String schemaSrc) throws IOException {
      Element[] originatingElements = new Element[classes.size() + 1];
      originatingElements[0] = annotatedElement;
      int i = 1;
      for (TypeMirror tm : classes) {
         originatingElements[i++] = types.asElement(tm);
      }

      boolean marshallersOnly = annotation.marshallersOnly();
      if (marshallersOnly && annotatedElement.getKind() != ElementKind.PACKAGE) {
         TypeMirror generatedSchemaType = elements.getTypeElement(GeneratedSchema.class.getName()).asType();
         if (types.isSubtype(annotatedElement.asType(), generatedSchemaType)) {
            reportError(annotatedElement, "@AutoProtoSchemaBuilder.marshallersOnly cannot be set to 'true' when the annotated element is a subtype of %s", GeneratedSchema.class.getName());
         }
      }

      String schemaResource = annotation.schemaFilePath();
      if (schemaResource.isEmpty()) {
         schemaResource = null;
      } else {
         if (marshallersOnly) {
            reportError(annotatedElement, "@AutoProtoSchemaBuilder.schemaFilePath cannot be set if @AutoProtoSchemaBuilder.marshallersOnly was set to 'true'");
         }

         if (!schemaResource.startsWith("/")) {
            schemaResource = '/' + schemaResource;
         }
         if (!schemaResource.endsWith("/")) {
            schemaResource = schemaResource + '/';
         }
         schemaResource += (fileName.startsWith("/") ? fileName.substring(1) : fileName);

         // write Protobuf schema as a resource file
         generatedFilesWriter.addSchemaResourceFile(schemaResource, schemaSrc, originatingElements);
      }

      if (annotation.service()) {
         // generate META-INF/services entry for this initializer implementation
         serviceLoaderFileGenerator.addProvider(initializerFQN, annotatedElement);
      }

      String initializerSrc = generateSerializationContextInitializer(annotatedElement, annotatedElementFQN, annotation,
            serCtxInitDeps, classes, generatedMarshallerClasses, packageName, initializerClassName, fileName, protobufPackageName,
            marshallersOnly ? null : schemaSrc, schemaResource);

      generatedFilesWriter.addInitializerSourceFile(initializerFQN, initializerSrc, originatingElements);
   }

   private String generateSerializationContextInitializer(Element annotatedElement, String annotatedElementFQN, AutoProtoSchemaBuilder annotation,
                                                          Set<String> serCtxInitDeps, Collection<? extends TypeMirror> classes, Set<String> generatedMarshallerClasses,
                                                          String packageName, String initializerClassName,
                                                          String fileName, String protobufPackageName,
                                                          String schemaSrc, String schemaResource) {
      IndentWriter iw = new IndentWriter();
      iw.append("/*\n");
      iw.append(" Generated by ").append(getClass().getName()).append("\n");
      iw.append(annotatedElement.getKind() == ElementKind.PACKAGE ? " for package " : " for class ").append(annotatedElementFQN).append("\n");
      iw.append(" annotated with ").append(String.valueOf(annotation)).append("\n");
      iw.append(" */\n\n");

      if (packageName != null) {
         iw.append("package ").append(packageName).append(";\n\n");
      }

      addGeneratedClassHeader(iw, classes);
      addSchemaBuilderAnnotation(iw, initializerClassName, fileName, annotation.schemaFilePath(), protobufPackageName,
            classes, serCtxInitDeps, annotation.autoImportClasses(), schemaSrc == null, annotation.service());

      iw.append("@SuppressWarnings(\"all\")\n");
      iw.append("public class ").append(initializerClassName);
      if (annotatedElement.getKind() == ElementKind.PACKAGE) {
         iw.append(" implements ").append(schemaSrc == null ? SerializationContextInitializer.class.getName() : GeneratedSchema.class.getName());
      } else {
         iw.append(annotatedElement.getKind() == ElementKind.INTERFACE ? " implements " : " extends ").append(annotatedElementFQN);
         if (schemaSrc != null) {
            TypeMirror generatedSchemaType = elements.getTypeElement(GeneratedSchema.class.getName()).asType();
            if (!types.isSubtype(annotatedElement.asType(), generatedSchemaType)) {
               iw.append(annotatedElement.getKind() == ElementKind.INTERFACE ? ", " : " implements ").append(GeneratedSchema.class.getName());
            }
         }
      }
      iw.append(" {\n\n");
      iw.inc();

      if (schemaSrc != null && schemaResource == null) {
         iw.append("private static final String PROTO_SCHEMA = ").append(makeStringLiteral(schemaSrc)).append(";\n\n");
      }

      int k = 0;
      for (String s : serCtxInitDeps) {
         iw.append("private final ").append(s).append(" dep").append(String.valueOf(k++)).append(" = new ").append(s).append("();\n\n");
      }

      if (schemaSrc != null) {
         iw.append("@Override\npublic String getProtoFileName() { return \"").append(fileName).append("\"; }\n\n");
         iw.append("@Override\npublic String getProtoFile() { return ");
         if (schemaResource == null) {
            iw.append("PROTO_SCHEMA");
         } else {
            iw.append("org.infinispan.protostream.impl.ResourceUtils.getResourceAsString(getClass(), \"").append(schemaResource).append("\")");
         }
         iw.append("; }\n\n");
         if (schemaResource != null) {
            iw.append("@Override\npublic java.io.Reader getProtoFileReader() { return ");
            iw.append("org.infinispan.protostream.impl.ResourceUtils.getResourceAsReader(getClass(), \"").append(schemaResource).append("\")");
            iw.append("; }\n\n");
         }
      } else {
         // TODO [anistor] See IPROTO-154. This 'else' branch must removed in ver. 5, once methods getProtoFileName()/getProtoFile() are removed from SerializationContextInitializer
         iw.append("@Override\npublic String getProtoFileName() { /* DUMMY! */ return \"").append(fileName).append("\"; }\n\n");
         iw.append("@Override\npublic String getProtoFile() { /* DUMMY! */ return \"// ").append(fileName).append("\"; }\n\n");
      }

      iw.append("@Override\n");
      iw.append("public void registerSchema(org.infinispan.protostream.SerializationContext serCtx) {\n");
      iw.inc();
      for (int j = 0; j < serCtxInitDeps.size(); j++) {
         iw.append("dep").append(String.valueOf(j)).append(".registerSchema(serCtx);\n");
      }
      if (schemaSrc != null) {
         iw.append("serCtx.registerProtoFiles(org.infinispan.protostream.FileDescriptorSource.fromString(getProtoFileName(), getProtoFile()));\n");
      }
      iw.dec();
      iw.append("}\n\n");

      iw.append("@Override\n");
      iw.append("public void registerMarshallers(org.infinispan.protostream.SerializationContext serCtx) {\n");
      iw.inc();
      for (int j = 0; j < serCtxInitDeps.size(); j++) {
         iw.append("dep").append(String.valueOf(j)).append(".registerMarshallers(serCtx);\n");
      }
      for (String name : generatedMarshallerClasses) {
         iw.append("serCtx.registerMarshaller(new ").append(name).append("());\n");
      }
      iw.dec();
      iw.append("}\n");

      iw.dec();
      iw.append("}\n");
      return iw.toString();
   }

   static void addGeneratedClassHeader(IndentWriter iw, boolean addPrivateJavadocTag, String... classes) {
      iw.append("/**\n * WARNING: Generated code! Do not edit!\n").append(addPrivateJavadocTag ? " *\n * @private\n" : "").append(" */\n");
      iw.append('@').append(Generated.class.getName()).append("(\n   value = \"").append(AutoProtoSchemaBuilderAnnotationProcessor.class.getName())
            .append("\",\n   comments = \"Please do not edit this file!\"\n)\n");
      if (classes.length > 0) {
         iw.append('@').append(OriginatingClasses.class.getName()).append("({\n");
         iw.inc();
         for (int i = 0; i < classes.length; i++) {
            if (i != 0) {
               iw.append(",\n");
            }
            iw.append(classes[i]).append(".class");
         }
         iw.append('\n');
         iw.dec();
         iw.append("})\n");
      }
   }

   private void addGeneratedClassHeader(IndentWriter iw, Collection<? extends TypeMirror> classes) {
      String[] names = new String[classes.size()];
      int i = 0;
      for (TypeMirror c : classes) {
         Name name = ((TypeElement) types.asElement(c)).getQualifiedName();
         names[i++] = name.toString();
      }
      addGeneratedClassHeader(iw, false, names);
   }

   // This annotation is added to generated code just for debugging/documentation purposes
   private static void addSchemaBuilderAnnotation(IndentWriter iw, String className, String schemaFileName,
                                                  String schemaFilePath, String schemaPackageName,
                                                  Collection<? extends TypeMirror> classes, Set<String> dependsOn,
                                                  boolean autoImportClasses, boolean marshallersOnly, boolean service) {
      iw.append("/*@").append(ANNOTATION_NAME).append("(\n");
      iw.inc();
      iw.append("className = \"").append(className).append("\",\n");
      iw.append("schemaFileName = \"").append(schemaFileName).append("\",\n");
      if (schemaFilePath != null) {
         iw.append("schemaFilePath = \"").append(schemaFilePath).append("\",\n");
      }
      if (schemaPackageName != null) {
         iw.append("schemaPackageName = \"").append(schemaPackageName).append("\",\n");
      }
      if (!dependsOn.isEmpty()) {
         iw.append("dependsOn = {\n");
         iw.inc();
         boolean first = true;
         for (String s : dependsOn) {
            if (first) {
               first = false;
            } else {
               iw.append(',');
            }
            iw.append('\n').append(s).append(".class");
         }
         iw.append('\n');
         iw.dec();
         iw.append("},\n");
      }
      iw.append("service = ").append(String.valueOf(service)).append(",\n");
      iw.append("marshallersOnly = ").append(String.valueOf(marshallersOnly)).append(",\n");
      iw.append("autoImportClasses = ").append(String.valueOf(autoImportClasses)).append(",\n");
      iw.append("includeClasses = {");
      iw.inc();
      boolean first = true;
      for (TypeMirror t : classes) {
         if (first) {
            first = false;
         } else {
            iw.append(',');
         }
         iw.append('\n').append(t.toString()).append(".class");
      }
      iw.append('\n');
      iw.dec();
      iw.append("}\n");
      iw.dec();
      iw.append(")*/\n");
   }

   private String makeStringLiteral(String s) {
      StringBuilder sb = new StringBuilder(s.length() + 2);
      sb.append('\"');
      for (int i = 0; i < s.length(); i++) {
         char ch = s.charAt(i);
         switch (ch) {
            case '\n':
               sb.append("\\n\" +\n\"");
               break;
            case '\"':
               sb.append("\\\"");
               break;
            case '\\':
               sb.append("\\\\");
               break;
            default:
               sb.append(ch);
         }
      }
      sb.append('\"');
      return sb.toString();
   }

   private String makeClassName(TypeElement e) {
      return makeNestedClassName(e, e.getSimpleName().toString());
   }

   private String makeNestedClassName(TypeElement e, String className) {
      Element enclosingElement = e.getEnclosingElement();
      if (enclosingElement instanceof PackageElement) {
         PackageElement packageElement = (PackageElement) enclosingElement;
         return packageElement.isUnnamed() ? className : packageElement.getQualifiedName() + "." + className;
      } else {
         TypeElement typeElement = (TypeElement) enclosingElement;
         return makeNestedClassName(typeElement, typeElement.getSimpleName() + "$" + className);
      }
   }
}
