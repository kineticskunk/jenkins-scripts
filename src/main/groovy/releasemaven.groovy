def release(components, boolean dryRun) {
    def result = [componentsReleased:[], errors: []]
    def componentListToPrint = ""
    def parallelBuild = [:]

    for ( int i = 0; i < components.size(); i++ ) {
        def c = components[i]
        componentListToPrint += "\n    - ${c.name}"

        parallelBuild[c.name] = {
            def scmUrl = "git@github.com:gravitee-io/${c.name}.git"
            def scmBranch = "master"
            node() {
                //stage "${c.name} v${c.version.releaseVersion()}"
                println("\n    scmUrl         = ${scmUrl}" +
                        "\n    scmBranch      = ${scmBranch}" +
                        "\n    releaseVersion = ${c.version.releaseVersion()}" +
                        "\n    nextSnapshot   = ${c.version.nextMinorSnapshotVersion()}")

                sh 'rm -rf *'
                sh 'rm -rf .git'

                def mvnHome = tool 'MVN33'
                def javaHome = tool 'JDK 8'
                def nodeHome = tool 'NodeJS 0.12.4'
                withEnv(["PATH+MAVEN=${mvnHome}/bin",
                        "PATH+NODE=${nodeHome}/bin",
                        "M2_HOME=${mvnHome}",
                        "JAVA_HOME=${javaHome}"]) {

                    checkout([
                            $class                           : 'GitSCM',
                            branches                         : [[
                                                                        name: "${scmBranch}"
                                                                ]],
                            doGenerateSubmoduleConfigurations: false,
                            extensions                       : [[
                                                                        $class     : 'LocalBranch',
                                                                        localBranch: "${scmBranch}"
                                                                ]],
                            submoduleCfg                     : [],
                            userRemoteConfigs                : [[
                                                                        credentialsId: 'ce78e461-eab0-44fb-bc8d-15b7159b483d',
                                                                        url          : "${scmUrl}"
                                                                ]]
                    ])

                    // set version
                    sh "mvn -B versions:set -DnewVersion=${c.version.releaseVersion()} -DgenerateBackupPoms=false"

                    // use release version of each -SNAPSHOT gravitee artifact
                    sh "mvn -B -U versions:update-properties -Dincludes=io.gravitee.*:* -DgenerateBackupPoms=false"

                    sh "cat pom.xml"

                    sh "git rev-parse HEAD > GIT_COMMIT"
                    def git_commit = readFile encoding: 'UTF-8', file: 'GIT_COMMIT'

                    withEnv(["GIT_COMMIT=${git_commit}"]) {
                        // deploy
                        if (dryRun) {
                            sh "mvn -B -U -DREDIS_HOST=${env.REDIS_TEST_HOST} -DREDIS_PORT=${env.REDIS_TEST_PORT} -DELASTIC_HOST=${env.ELASTIC_TEST_HOST} -DELASTIC_PORT=${env.ELASTIC_TEST_PORT} clean install"
                            sh "mvn enforcer:enforce"
                        } else {
                            sh "mvn -B -U -DREDIS_HOST=${env.REDIS_TEST_HOST} -DREDIS_PORT=${env.REDIS_TEST_PORT} -DELASTIC_HOST=${env.ELASTIC_TEST_HOST} -DELASTIC_PORT=${env.ELASTIC_TEST_PORT} -P gravitee-release clean deploy"
                        }
                    }

                    // commit, tag the release
                    sh "git add --update"
                    sh "git commit -m 'release(${c.version.releaseVersion()})'"
                    sh "git tag ${c.version.releaseVersion()}"

                    // update next version
                    sh "mvn -B versions:set -DnewVersion=${c.version.nextMinorSnapshotVersion()} -DgenerateBackupPoms=false"

                    // commit, tag the snapshot
                    sh "git add --update"
                    sh "git commit -m 'chore(): Prepare next version'"

                    // push
                    if ( !dryRun ) {
                        sh "git push --tags origin ${scmBranch}"
                    }
                }
                result.componentsReleased.add(c)
            }
        }
    }

    try {
        println("    Release ${components.size()} components in parallel : ${componentListToPrint} \n")
        stage "Release ${components.size()} components"
        parallel parallelBuild
    } catch(err) {
        echo "Exception thrown:\n ${err}"
        result.errors.add(err)
    } finally {
        return result
    }

}

return this