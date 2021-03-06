package org.jikesrvm.classloader;

import org.jikesrvm.Callbacks;
import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

// Velodrome: Context: Changed this class extensively to support two static contexts (transactional and non-transactional) 
// for application methods. VM and library methods are not instrumented by AVD or Velodrome, so it is not required to 
// distinguish their contexts. Hence they currently have only one VM context. Application methods which override VM or
// library methods will have an additional RVMMethod with VM context as resolved context.

/** Octet: Static cloning: Represent context statically, e.g., application versus VM context. */
@Uninterruptible
public final class Context implements Constants {

  public static final int VM_CONTEXT = 0;
  public static final int TRANS_CONTEXT = 1;
  public static final int NONTRANS_CONTEXT = 2;
  public static final int INVALID_CONTEXT = -1;
  
  // This seems to work okay, but maybe the stack-walking isn't checking JNI calls correctly.
  // Octet: TODO: trying this -- it seems like the right thing to do
  public static final int JNI_CONTEXT = VM_CONTEXT; //APP_CONTEXT;

  // Not clear what we should do about finalizers.
  public static final int FINALIZER_CONTEXT = VM_CONTEXT;

  private static final int[] LIBRARY_CONTEXT_ONLY = { VM_CONTEXT }; // Library methods
  private static final int[] VM_CONTEXT_ONLY = { VM_CONTEXT }; // VM methods
  // Most application methods, certain application methods can also possibly override library methods
  private static final int[] APP_CONTEXTS = { TRANS_CONTEXT, NONTRANS_CONTEXT }; 
  
  @Inline
  public static boolean isTRANSContext(int context) {
    return (context == TRANS_CONTEXT);
  }
  
  @Inline
  public static boolean isNONTRANSContext(int context) {
    return (context == NONTRANS_CONTEXT);
  }
  
  @Pure
  static int getLoneStaticContext(MethodReference methodRef) {
    int[] contexts = getStaticContexts(methodRef);
    if (VM.VerifyAssertions) { VM._assert(contexts.length == 1); }
    return contexts[0];
  }
  
  @Pure
  static int[] getStaticContexts(MethodReference methodRef) {
    return getStaticContexts(methodRef.getType());
  }

  @Pure
  static int[] getStaticContexts(TypeReference typeRef) {
    if (isLibraryPrefix(typeRef)) {
      return LIBRARY_CONTEXT_ONLY;
    } else if (isVMPrefix(typeRef)) {
      return VM_CONTEXT_ONLY;
    } else {
      return APP_CONTEXTS;
    }
  }

  @Pure
  public static boolean isLibraryPrefix(TypeReference typeRef) {
    return typeRef.getName().isPrefix(LIBRARY_PREFIXES);
  }

  @Pure
  public static boolean isVMPrefix(TypeReference typeRef) {
    return typeRef.getName().isPrefix(VM_PREFIXES);
  }

  @Pure
  public static boolean isApplicationPrefix(TypeReference typeRef) {
    return !isLibraryPrefix(typeRef) && !isVMPrefix(typeRef);
  }

  /* Velodrome: There is a complicated relationship between few packages. For example, gnu/javax packages extend
   * javax.* packages. Again javax.xml.* like javax.xml.* files call org.xml.sax.* methods directly.
   * Another example is in HEDC: Lgnu/java/net/protocol/http/HTTPURLConnection; which is considered to be a library 
   * method extends Ljavax/net/ssl/HttpsURLConnection; which is an application method.
   * */
  private static final byte[][] LIBRARY_PREFIXES =
  {"Ljava/".getBytes(),
   "Lgnu/java/".getBytes(), // There is a gnu/javax package also.
   "Lgnu/classpath/".getBytes(),
   "Lsun/misc/Unsafe".getBytes(),
   //"Ljavax/".getBytes(), // Velodrome: Many benchmarks like sunflow9, xalan9 make use of this package
   //"Lorg/xml/".getBytes(),
  };

  private static final byte[][] VM_PREFIXES = {
    "Lorg/jikesrvm/".getBytes(),
    "Lorg/mmtk/".getBytes(),
    "Lorg/vmutil/".getBytes(),
    "Lorg/vmmagic/".getBytes(),
    "Lcom/ibm/tuningfork/".getBytes(),
    "L$Proxy".getBytes(),
  };

  @Pure
  public static boolean hasMultipleStaticContexts(TypeReference typeRef) {
    return getStaticContexts(typeRef).length > 1;
  }
  
  @Pure
  public static int getNumberOfStaticContexts(TypeReference typeRef) {
    return getStaticContexts(typeRef).length;
  }

  @Pure
  public static boolean hasMultipleStaticContexts(MethodReference methodRef) {
    return hasMultipleStaticContexts(methodRef.getType());
  }

  static boolean isMatch(RVMMethod rvmMethod, int context, boolean resolve) {
    if (rvmMethod.getMemberRef().asMethodReference().hasMultipleResolvedContexts()) { // Application methods
      return rvmMethod.getResolvedContext() == context;
    } else if (!hasMultipleStaticContexts(rvmMethod.getMemberRef().asMethodReference())) { // Library and VM methods
      if (resolve) { // We wan't to have precise checks over here so as to be able to clone methods
        return rvmMethod.getStaticContext() == context;
      } else { // Otherwise it doesn't matter which context library/VM methods get called from 
        return true;
      }
    } else {
      // Velodrome: There could be application classes that implement interfaces, but don't override an abstract method. In
      // such cases, the application class will have only one virtual method, with resolved context set to VM_CONTEXT, 
      // and static context depending on the meet() operation.
      // Example: Lhedc/MetaSearchResultIterator;.next() which does not implement Ljava/util/Iterator;.next() 
      if (hasMultipleStaticContexts(rvmMethod.getMemberRef().asMethodReference())) {
        return true;
      } else {
        if (VM.VerifyAssertions) { VM._assert(NOT_REACHED); }
        return false;
      }
    }
  }

  @Pure
  static int getOtherContext(RVMMethod method, int context) {
    if (VM.VerifyAssertions) { VM._assert(context != VM_CONTEXT); }
    if (context == TRANS_CONTEXT) {
      return NONTRANS_CONTEXT;
    } else {
      if (VM.VerifyAssertions) { VM._assert(context == NONTRANS_CONTEXT); }
      return TRANS_CONTEXT;
    }
  }

  @Pure
  static int getStaticContext(MethodReference methodRef, int resolvedContext) {
    if (hasMultipleStaticContexts(methodRef)) {
      return resolvedContext;
    } else {
      return getLoneStaticContext(methodRef);
    }
  }

  @Pure
  static String getName(int context) {
    switch (context) {
      case VM_CONTEXT: return "VM";
      case TRANS_CONTEXT: return "TRANS";
      case NONTRANS_CONTEXT: return "NONTRANS";
      case INVALID_CONTEXT: return "INVALID";
      default:
        if (VM.VerifyAssertions) { VM._assert(NOT_REACHED); }
        return "null";
    }
  }
  
  // Rest of this class is for debugging:

  /** If enabled, dynamic instrumentation at every library method prologue checks that the contexts of the caller and callee match. */
  public static final boolean DEBUG = VM.VerifyAssertions && false; // true

//  private static final Atom reflectionBase = Atom.findOrCreateAsciiAtom("Lorg/jikesrvm/classloader/ReflectionBase;");
//  private static final Atom invokeInternal = Atom.findOrCreateAsciiAtom("invokeInternal");

  @NoInline
  @Entrypoint
  public static void checkLibraryContext() {
//    if (VM.VerifyAssertions) { VM._assert(Context.DEBUG); }
//    Address calleeFP = Magic.getCallerFramePointer(Magic.getFramePointer());
//    Address ip = Magic.getReturnAddress(calleeFP);
//    Address callerFP = Magic.getCallerFramePointer(calleeFP);
//    RVMMethod callee = CompiledMethods.getCompiledMethod(Magic.getCompiledMethodID(calleeFP)).method;
//    int callerCMID = Magic.getCompiledMethodID(callerFP);
//    if (callerCMID == StackframeLayoutConstants.INVISIBLE_METHOD_ID) {
//      ip = Magic.getReturnAddress(callerFP);
//      callerFP = Magic.getCallerFramePointer(callerFP);
//      callerCMID = Magic.getCompiledMethodID(callerFP);
//    }
//    CompiledMethod callerCM = CompiledMethods.getCompiledMethod(callerCMID);
//    RVMMethod caller = null;
//    if (VM.BuildForOptCompiler && callerCM.getCompilerType() == CompiledMethod.OPT) {
//      OptCompiledMethod optInfo = (OptCompiledMethod)callerCM;
//      // Opt stack frames may contain multiple inlined methods.
//      OptMachineCodeMap map = optInfo.getMCMap();
//      Offset instructionOffset = callerCM.getInstructionOffset(ip);
//      int iei = map.getInlineEncodingForMCOffset(instructionOffset);
//      if (iei >= 0) {
//        MethodReference callerRef = null;
//        int context = INVALID_CONTEXT;
//        int[] inlineEncoding = map.inlineEncoding;
//        for (; iei >= 0 && context == INVALID_CONTEXT; iei = OptEncodedCallSiteTree.getParent(iei, inlineEncoding)) {
//          int mid = OptEncodedCallSiteTree.getMethodID(iei, inlineEncoding);
//          // Set callerRef to the leaf method 
//          MethodReference methodRef = MemberReference.getMemberRef(mid).asMethodReference();
//          if (callerRef == null) {
//            callerRef = methodRef;
//          }
//          // Keep going until we find a lone context (i.e., a non-library method)
//          if (!Context.hasMultipleStaticContexts(methodRef)) {
//            context = Context.getLoneStaticContext(methodRef);
//          }
//        }
//        if (context == INVALID_CONTEXT) {
//          context = callerCM.method.getStaticContext();
//        }
//        caller = callerRef.getResolvedMember(context);
//      }
//    }
//    if (caller == null) {
//      caller = callerCM.method;
//    }
//    VM._assert(hasMultipleStaticContexts(callee.getMemberRef().asMethodReference()));
//    if (hasMultipleStaticContexts(caller.getMemberRef().asMethodReference())) {
//      if (caller.getStaticContext() != callee.getStaticContext()) {
//        VM.sysWrite("caller = ");
//        VM.sysWrite(caller);
//        VM.sysWrite(", callee = ");
//        VM.sysWrite(callee);
//        VM.sysWriteln();
//        VM.sysWrite("caller.context = ", getName(caller.getStaticContext()));
//        VM.sysWriteln("; callee.context = ", getName(callee.getStaticContext()));
//        VM.sysWrite("caller.contextMatters = ", Context.hasMultipleStaticContexts(caller.getMemberRef().asMethodReference()));
//        VM.sysWriteln("; callee.contextMatters = ", Context.hasMultipleStaticContexts(callee.getMemberRef().asMethodReference()));
//        VM._assert(false);
//      }
//      if (caller.getStaticContext() == VM_CONTEXT) {
//        libCalledByLibInVmContext++;
//      } else {
//        VM._assert(caller.getStaticContext() == APP_CONTEXT);
//        libCalledByLibInAppContext++;
//      }
//    } else {
//      if (getLoneStaticContext(caller.getMemberRef().asMethodReference()) == APP_CONTEXT) {
//        VM._assert(callee.getStaticContext() == APP_CONTEXT);
//        VM._assert(caller.getStaticContext() == APP_CONTEXT);
//        libCalledByApp++;
//      } else {
//        // Ignore reflection, at least for now
//        if (caller.getDeclaringClass().getResolvedClassForType() == org.jikesrvm.runtime.Reflection.class ||
//            caller.getDeclaringClass().getResolvedClassForType() == org.jikesrvm.runtime.ReflectionBase.class ||
//            (caller.getDeclaringClass().getEnclosingClass() != null &&
//             caller.getDeclaringClass().getEnclosingClass().getName() == reflectionBase) ||
//            caller.getName() == invokeInternal) {
//        } else {
//          VM._assert(callee.getStaticContext() == VM_CONTEXT);
//          VM._assert(caller.getStaticContext() == VM_CONTEXT);
//        }
//        libCalledByVm++;
//      }
//    }
  }

  static long libCalledByLibInVmContext;
  static long libCalledByLibInAppContext;
  static long libCalledByVm;
  static long libCalledByApp;
  
  static {
    if (DEBUG) {
      Callbacks.addExitMonitor(new Callbacks.ExitMonitor() {      
        public void notifyExit(int value) {
          System.out.println("libCalledByLibInVmContext: " + libCalledByLibInVmContext);
          System.out.println("libCalledByLibInAppContext: " + libCalledByLibInAppContext);
          System.out.println("libCalledByVm: " + libCalledByVm);
          System.out.println("libCalledByApp: " + libCalledByApp);
        }
      });
    }
  }

}
