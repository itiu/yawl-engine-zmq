rm pom.xml
sed 's/<packaging>war/<packaging>jar/g' pom.xml- > pom.xml
mvn install
cp pom.xml- pom.xml
mvn install
