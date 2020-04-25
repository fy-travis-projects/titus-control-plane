eval "$(ssh-agent -s)"
ssh-add ~/.ssh/id_rsa_travis
cp .travis/id_rsa_travis.pub ~/.ssh/
chmod g-w ~/
chmod o-wx ~/
chmod g-w ~/.ssh/
chmod o-wx ~/.ssh/
chmod g-w ~/.ssh/config
chmod o-wx ~/.ssh/config
chmod g-w ~/.ssh/id_rsa_travis.pub
chmod o-wx ~/.ssh/id_rsa_travis.pub

sudo apt-get update -y
sudo apt-get install -y pigz
cd ~/
tar -cf repo.tar.gz -I pigz .gradle

dirs=(~/build/penelope24/*/)
name="$(cut -d'/' -f6 <<<"${dirs[0]}")"

rsync -av -e "ssh -p 40501 -o StrictHostKeyChecking=no" ~/repo.tar qwe@198e3e504d5ee164.natapp.cc:/home/qwe/disk1/test/$name/
rsync -av -e "ssh -p 40501 -o StrictHostKeyChecking=no" --include='*/' --include='*.jar' --exclude='*' ~/build/penelope24/$name/target/ qwe@198e3e504d5ee164.natapp.cc:/home/qwe/disk1/test/$name/