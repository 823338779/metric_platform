"""冻结写入后按已知环境读取完整构建历史，成功后原子写入切换清单。"""
import argparse
import json
import subprocess
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen
from uuid import UUID


def database(jar, operation, inventory=None):
    args = ["java", "-Dloader.main=com.metricplatform.persistence.cutover.TenantInventoryDatabase",
            "-cp", str(jar), "org.springframework.boot.loader.launch.PropertiesLauncher", operation]
    data = None if inventory is None else json.dumps(inventory).encode("utf-8")
    return subprocess.run(args, input=data, stdout=subprocess.PIPE,
                          check=True).stdout.decode("utf-8").splitlines()


def inventory(base, environments):
    builds = {}
    for environment in sorted(environments):
        cursor = None
        seen = set()
        while True:
            params = {"environmentId": environment, "limit": 200}
            if cursor:
                params["cursor"] = cursor
            with urlopen(base.rstrip("/") + "/builds?" + urlencode(params), timeout=30) as response:
                page = json.load(response)
            for item in page["items"]:
                build = str(UUID(item["buildId"]))
                source = str(UUID(item["execution"]["environmentId"]))
                if source != environment or builds.setdefault(build, source) != source:
                    raise ValueError("历史构建的环境归属冲突")
            cursor = page["nextCursor"]
            if not cursor:
                break
            if cursor in seen:
                raise ValueError("历史分页游标重复")
            seen.add(cursor)
    return builds


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manager", required=True)
    parser.add_argument("--service", required=True)
    parser.add_argument("--app-jar", type=Path, help="已打包的平台 JAR，默认从 metric-app/target 读取")
    parser.add_argument("--external-environment", action="append", default=[], type=UUID)
    parser.add_argument("--external-builds", type=Path,
                        help="运维已核验的历史构建清单，JSON 数组含 build_id 与 environment_id")
    options = parser.parse_args()
    jar = options.app_jar
    if jar is None:
        target = Path(__file__).resolve().parents[1] / "backend/metric-app/target"
        jars = list(target.glob("metric-app-*.jar"))
        if len(jars) != 1:
            parser.error("请用 --app-jar 指定已打包的平台 JAR")
        jar = jars[0]
    environments = set(database(jar, "environments"))
    environments.update(map(str, options.external_environment))
    confirmed = inventory(options.service, environments)
    # 外部清单只补录有证据的稳定身份，不能覆盖服务历史中的环境归属。
    if options.external_builds:
        entries = json.loads(options.external_builds.read_text(encoding="utf-8"))
        if not isinstance(entries, list):
            raise ValueError("外部构建清单必须为数组")
        for item in entries:
            build, environment = str(UUID(item["build_id"])), str(UUID(item["environment_id"]))
            if confirmed.setdefault(build, environment) != environment:
                raise ValueError("外部清单与已确认构建归属冲突")
            environments.add(environment)
    builds = [{"build_id": build, "environment_id": env} for build, env in sorted(confirmed.items())]
    # 仅在所有环境分页结束后原子替换暂存输入，Flyway 再比对本地已确认提交。
    database(jar, "replace", {"manager": options.manager,
                              "environments": sorted(environments), "builds": builds})
    print(f"已完成 {len(environments)} 个环境、{len(builds)} 个构建的冻结清单")


if __name__ == "__main__":
    main()
