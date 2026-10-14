#!/usr/bin/env python3
"""按 World API 调用次数比较优化前的真实源码与当前代码。
需要 Java 25、Maven、git，并先执行一次 `mvn test` 以生成测试类路径。
不需要服务器、客户端资源、模型 API，也不需要接受 EULA。
"""
import argparse
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline', default='1a3751d8b60d92ae81dfaabbf7aca91d3b3aa892')
parser.add_argument('--maven', default='mvn')
parser.add_argument('--repo-local')
parser.add_argument('--offline', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
report = root / 'target/surefire-reports/TEST-cn.panda.pandagpt.vision.BlockRaycasterTest.xml'
classpath = next(p.attrib['value'] for p in ET.parse(report).findall('./properties/property') if p.attrib['name'] == 'java.class.path')
output = root / 'target/performance-baseline'
sources = output / 'src'; sources.mkdir(parents=True, exist_ok=True)
files = []
for name in ('VoxelMarcher', 'BlockRaycaster'):
    original = subprocess.check_output(['git', 'show', f'{args.baseline}:src/main/java/cn/panda/pandagpt/vision/{name}.java'], cwd=root, text=True)
    original = original.replace('package cn.panda.pandagpt.vision;', 'package cn.panda.pandagpt.vision.baseline;\nimport cn.panda.pandagpt.vision.Camera;\nimport cn.panda.pandagpt.vision.RaySample;')
    path = sources / f'{name}.java'; path.write_text(original); files.append(str(path))
javac = str(Path(os.environ['JAVA_HOME']) / 'bin/javac') if 'JAVA_HOME' in os.environ else 'javac'
subprocess.run([javac, '-cp', classpath, '-d', str(output), *files], check=True, cwd=root)
command = [args.maven, '-B', '-ntp', 'test', '-Dtest=PerformanceWorkloadTest', f'-Dpandagpt.benchmark.baseline={output}']
if args.repo_local:
    command.append(f'-Dmaven.repo.local={args.repo_local}')
if args.offline:
    command.append('-o')
subprocess.run(command, check=True, cwd=root)
print((root / 'target/performance-results.json').read_text())
