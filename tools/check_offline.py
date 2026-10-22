#!/usr/bin/env python3
"""Run domain JUnit tests and Kotlin syntax checks with a cached Kotlin compiler.

No Android build or real serialization is performed. Set KOTLIN_COMPILER_LIB to
an existing Kotlin/Gradle lib directory containing compiler, stdlib, JUnit4 and
Hamcrest JARs. The default discovers the local Gradle cache without changing it.
"""
import glob
import os
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
candidates=glob.glob(str(Path.home()/'.gradle/wrapper/dists/gradle-*/**/lib'),recursive=True)
compiler_lib=os.environ.get('KOTLIN_COMPILER_LIB') or next((p for p in candidates if glob.glob(p+'/kotlin-compiler-embeddable*.jar') and glob.glob(p+'/junit-4*.jar')),None)
if not compiler_lib:
    raise SystemExit('Set KOTLIN_COMPILER_LIB to a cached Kotlin compiler lib directory with JUnit4/Hamcrest.')
lib=Path(compiler_lib)
classpath=os.pathsep.join(str(p) for p in lib.glob('*.jar') if p.name.startswith(('kotlin','trove','annotations','junit','hamcrest')))

def run(*args):
    result=subprocess.run([str(a) for a in args],cwd=ROOT)
    if result.returncode:
        raise SystemExit(f"{args[0]} exited with {result.returncode}")

with tempfile.TemporaryDirectory(prefix='sms-forwarder-checks-') as folder:
    out=Path(folder)
    run('javac','-cp',classpath,'-d',out,ROOT/'tools/offline/KotlinSyntaxCheck.java')
    run('java','-cp',str(out)+os.pathsep+classpath,'KotlinSyntaxCheck',ROOT/'app/src')
    sources=[ROOT/'app/src/main/java/ua/smsforwarder/core/Models.kt',ROOT/'app/src/main/java/ua/smsforwarder/core/I18n.kt',ROOT/'app/src/test/java/ua/smsforwarder/core/I18nTest.kt',ROOT/'app/src/main/java/ua/smsforwarder/delivery/RetryPolicy.kt',ROOT/'tools/offline/SerializationAnnotations.kt',ROOT/'tools/offline/Json.kt',ROOT/'app/src/test/java/ua/smsforwarder/core/RuleEngineTest.kt',ROOT/'app/src/test/java/ua/smsforwarder/core/RetryPolicyTest.kt']
    run('java','-cp',classpath,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',classpath,'-jvm-target','1.8','-d',out,*sources)
    run('java','-cp',str(out)+os.pathsep+classpath,'org.junit.runner.JUnitCore','ua.smsforwarder.core.RuleEngineTest','ua.smsforwarder.core.RetryPolicyTest','ua.smsforwarder.core.I18nTest')
