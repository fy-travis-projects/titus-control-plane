# set up ssh setting
eval "$(ssh-agent -s)"
ssh-add ~/.ssh/id_rsa_travis
chmod g-w ~/
chmod o-wx ~/
chmod g-w ~/.ssh/
chmod o-wx ~/.ssh/
chmod g-w ~/.ssh/config
chmod o-wx ~/.ssh/config

# get current project name
dirs=(/home/travis/build/fy-travis-projects/*)
name="$(cut -d'/' -f6 <<<"${dirs[0]}")"
echo $name

# install 7z tool
# sudo apt-get install p7zip-full -y

# find all 3rd party jars, move them into a tmp folder
cd $HOME 
mkdir lib
cd .gradle/caches/modules-2/files-2.1
find . -name '*.jar' -exec mv {} $HOME/lib \;

# find all project artifact jars, move them into a tmp folder
cd $HOME
mkdir project
cd $HOME/build/fy-travis-projects/$name
find . -name '*.jar' -exec mv {} $HOME/project \;
cd $HOME
# 7z a -r projects.7z tmp2
# ls -hl projects.7z

# check th results before deploy
pwd
ls -al

# use rsync to deploy to google vm server
rsync -W -e "ssh -o StrictHostKeyChecking=no -o Compression=no" --info=progress2 lib travis@35.236.128.26:/home/travis/projects/$name/
rsync -W -e "ssh -o StrictHostKeyChecking=no -o Compression=no" --info=progress2 project travis@35.236.128.26:/home/travis/projects/$name/



