import java.nio.file.*;
import org.jetbrains.kotlin.cli.jvm.compiler.*;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.psi.KtPsiFactory;

public class KotlinSyntaxCheck {
    public static void main(String[] args) throws Exception {
        var disposable=Disposer.newDisposable();
        int errors=0, files=0;
        try {
            var config=new CompilerConfiguration();
            config.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY,MessageCollector.Companion.getNONE());
            var env=KotlinCoreEnvironment.createForProduction(disposable,config,EnvironmentConfigFiles.JVM_CONFIG_FILES);
            var factory=new KtPsiFactory(env.getProject(),false);
            try(var paths=Files.walk(Path.of(args[0]))) {
                for(var path:paths.filter(p->p.toString().endsWith(".kt")).toList()) {
                    files++;
                    var file=factory.createFile(path.getFileName().toString(),Files.readString(path));
                    for(var error:PsiTreeUtil.collectElementsOfType(file,PsiErrorElement.class)) {
                        System.err.println(path+":"+error.getTextOffset()+": "+error.getErrorDescription());
                        errors++;
                    }
                }
            }
        } finally { Disposer.dispose(disposable); }
        System.out.println("Kotlin syntax: "+files+" files, "+errors+" errors");
        if(errors!=0) System.exit(1);
    }
}
