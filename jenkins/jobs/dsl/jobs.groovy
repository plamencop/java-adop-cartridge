// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

//Repositories
def solutioncodebase = "ssh://jenkins@gerrit:29418/Workspace/Java/java-faculty.git"


//Jobs
def build = freeStyleJob(projectFolderName + "/FestivalPortal_Build")
def codeanalysis = freeStyleJob(projectFolderName + "/FestivalPortal_CodeAnalysis")
def testautomation = freeStyleJob(projectFolderName + "/FestivalPortal_TestAutomation")
def junit = freeStyleJob(projectFolderName + "/FestivalPortal_JUnit")
def nexus = freeStyleJob(projectFolderName + "/FestivalPortal_Deploy_to_Nexus")
def deptodev = freeStyleJob(projectFolderName + "/FestivalPortal_Deploy_to_Dev")

def usecase2_pipeline = buildPipelineView(projectFolderName + "/FestivalPortal_Pipeline")

usecase2_pipeline.with{
    title('Festival Portal Pipeline')
    displayedBuilds(5)
    selectedJob(projectFolderName + "/FestivalPortal_CodeAnalysis")
    showPipelineParameters()
    refreshFrequency(5)
}


codeanalysis.with{
label('java8')
scm{
    git{
      remote{
        url(solutioncodebase)
        credentials("adop-jenkins-master")
      }
      branch("*/master")
    }
  }
steps {
        maven{
          rootPOM('FestivalPortal/pom.xml')
          goals('clean install')
          mavenInstallation("ADOP Maven")
        }
		shell('''#!/bin/bash
		echo "Code Analysis on $BUILD is complete!!"
		''')
		maven{
		  rootPOM('FestivalPortal/pom.xml')
          goals('clean org.jacoco:jacoco-maven-plugin:prepare-agent install')
          mavenInstallation("ADOP Maven")
		}
		shell('''#!/bin/bash
		echo "ANALYSIS SUCCESSFUL, you can browse http://$(curl http://169.254.169.254/latest/meta-data/public-ipv4)/sonar/dashboard/index/FestivalPortal:FestivalPortal"
		''')	
    }
publishers {
		downstreamParameterized {
            trigger(projectFolderName + "/FestivalPortal_Build") {
                condition('SUCCESS')
                parameters {
                    predefinedProp("Build",'${BUILD}')
                }
            }
        }
    }
}

build.with{
label('java8')
scm{
    git{
      remote{
        url(solutioncodebase)
        credentials("adop-jenkins-master")
      }
      branch("*/master")
    }
  }
triggers {
       gerrit {
            events {
                refUpdated()
            }
            project('plain:JavaTraining/Faculty/solutioncodebase', ['path:origin/refs/master'])
        }
    }
steps {
        maven{
          rootPOM('FestivalPortal/pom.xml')
          goals('compile')
          mavenInstallation("ADOP Maven")
        }
		shell('''#!/bin/bash
		set -e
		git_data=$(git --git-dir "${WORKSPACE}/.git" log -1 --pretty="format:%an<br/>%s%b")
		echo "GIT_LOG_DATA=${git_data}" > git_log_data.properties
		''')
		environmentVariables {
        propertiesFile('git_log_data.properties')
		}
		systemGroovyCommand('''import hudson.model.*; 
					  import hudson.util.*;
					  // Get current build number
					  def currentBuildNum = build.getEnvironment(listener).get('BUILD_NUMBER')
					  println "Build Number: " + currentBuildNum

					 // Get Git Data
					 def gitData = build.getEnvironment(listener).get('GIT_LOG_DATA')
					 println "Git Data: " + gitData;

					 def currentBuild = Thread.currentThread().executable;
					 oldParams = currentBuild.getAction(ParametersAction.class)

					 // Update the param
					 def params = [ new StringParameterValue("T",gitData), new StringParameterValue("B",currentBuildNum) ]

					 // Remove old params - Plugins inject variables!
					 currentBuild.actions.remove(oldParams)
					 currentBuild.addAction(new ParametersAction(params));''')

		
    }
publishers {
        archiveArtifacts('FestivalPortal/target/*.war')
		downstreamParameterized {
            trigger(projectFolderName + "/FestivalPortal_JUnit") {
                condition('SUCCESS')
                parameters {
                    predefinedProp("Build",'${BUILD}')
                }
            }
        }
    }
}

junit.with{
scm{
    git{
      remote{
        url(solutioncodebase)
        credentials("adop-jenkins-master")
      }
      branch("*/master")
    }
  }
steps {
maven{
	rootPOM('FestivalPortal/pom.xml')
	goals('test')
	mavenInstallation("ADOP Maven")
}
}
publishers {
		downstreamParameterized {
            trigger(projectFolderName + "/FestivalPortal_Deploy_to_Nexus") {
                condition('SUCCESS')
                parameters {
                    predefinedProp("Build",'${BUILD}')
                }
            }
        }
    } 
}

nexus.with{
//NEXUS
//Error 413: Entity too large
//sol. nginx.conf
       //client_max_body_size 2M;

//Error 401: Auth something... (repo isn't accessible)
//sol. use different repo

//Error: file already exist
//sol. Dversion=$BUILD_ID

scm{
    git{
      remote{
        url(solutioncodebase)
        credentials("adop-jenkins-master")
      }
      branch("*/master")
    }
  }
 steps{
	maven{
		rootPOM('FestivalPortal/pom.xml')
		goals('deploy:deploy-file 
    -Durl=http://52.50.49.253/nexus/content/repositories/releases/
    -DrepositoryId=releases
    -DgroupId=org.java
    -DartifactId=festivalportal
    -Dversion=$BUILD_ID
    -Dpackaging=war
    -Dfile=$WORKSPACE/archive/FestivalPortal/target/FestivalPortal.war')
		mavenInstallation("ADOP Maven")
		}
	}
publishers {
		downstreamParameterized {
            trigger(projectFolderName + "/FestivalPortal_Deploy_to_Dev") {
                condition('SUCCESS')
                parameters {
                    predefinedProp("Build",'${BUILD}')
                }
            }
        }
    }
}

deptodev.with{
scm{
    git{
      remote{
        url(solutioncodebase)
        credentials("adop-jenkins-master")
      }
      branch("*/master")
    }
  }
wrappers {
		configure { project ->
    	project / 'buildWrappers' / 'org.jenkinsci.plugins.environmentdashboard.DashboardBuilder' {
        			nameOfEnv("JavaTraining-Faculty-CI")
        			componentName("FestivalPortal")
        			buildNumber('$(BUILD_NUMBER)')
				buildjob("")
				packageName("")
				addColumns("false")
      	  		}
			}
        sshAgent("adop-jenkins-master")
    }
steps {

configure { projects ->
    	projects / 'builders' / 'hudson.plugins.copyartifact.CopyArtifact' {
        			project("FestivalPortal_Build")
        			filter("FestivalPortal/target/FestivalPortal.war")
        			target("")
				excludes("")
				doNotFingerprintArtifacts("false")
      	  		}
	   } 
    }
	configure { project ->
    	project / 'publishers' / 'hudson.plugins.deploy.DeployPublisher' {
      		'adapters' {
          		'hudson.plugins.deploy.tomcat.Tomcat7xAdapter' {
        			userName("john.smith")
        			passwordScrambled("UGFzc3dvcmQwMQ==")
        			url("http://52.48.181.225/")
      	  		}
      	}
      	contextPath("")
      	war("FestivalPortal/target/FestivalPortal.war")
      	onFailure("false")
    	}
	}
publishers {
		downstreamParameterized {
            trigger(projectFolderName + "/FestivalPortal_TestAutomation") {
                condition('SUCCESS')
                parameters {
                    predefinedProp("Build",'${BUILD}')
                }
            }
        }
    }

}

testautomation.with{
	scm{
    git{
      remote{
        url(solutioncodebase)
        credentials("adop-jenkins-master")
      }
      branch("*/master")
    }
  }
wrappers {
preBuildCleanup()	
}
steps {
maven{
	rootPOM('FestivalPortal/pom.xml')
	goals('test')
	mavenInstallation("ADOP Maven")
}
}


}








